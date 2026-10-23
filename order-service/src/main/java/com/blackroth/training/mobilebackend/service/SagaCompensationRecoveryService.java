package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderItem;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recovers orders whose downstream Saga compensation previously failed or timed out.
 * Ensures data consistency across application restarts by querying persisted
 * COMPENSATION_PENDING orders and re-executing idempotent inventory releases.
 * Implements bounded retries, diagnostic failure tracking, and manual operator reconciliation.
 */
@Service
public class SagaCompensationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SagaCompensationRecoveryService.class);

    public static final int MAX_RECOVERY_ATTEMPTS = 5;

    public record CompensationDiagnostic(
            Long orderId,
            Long userId,
            BigDecimal totalAmount,
            int attempts,
            int maxAttempts,
            String failureType,
            String lastFailureReason,
            Instant firstAttemptAt,
            Instant lastAttemptAt,
            boolean requiresManualIntervention
    ) {}

    private final ConcurrentHashMap<Long, Integer> recoveryAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompensationDiagnostic> diagnostics = new ConcurrentHashMap<>();
    private final Set<Long> loggedExhaustedOrders = ConcurrentHashMap.newKeySet();

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final OrderStateService orderStateService;
    private final com.blackroth.training.mobilebackend.metrics.EventMetrics eventMetrics;

    @Value("${saga.recovery.enabled:true}")
    private boolean enabled = true;

    public SagaCompensationRecoveryService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OrderStateService orderStateService
    ) {
        this(orderRepository, productClient, orderStateService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SagaCompensationRecoveryService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OrderStateService orderStateService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.blackroth.training.mobilebackend.metrics.EventMetrics eventMetrics
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.orderStateService = orderStateService;
        this.eventMetrics = eventMetrics;
    }

    public int getRecoveryAttempts(Long orderId) {
        return recoveryAttempts.getOrDefault(orderId, 0);
    }

    public void resetRecoveryAttempts(Long orderId) {
        recoveryAttempts.remove(orderId);
        diagnostics.remove(orderId);
        loggedExhaustedOrders.remove(orderId);
    }

    public List<CompensationDiagnostic> getExhaustedDiagnostics() {
        return List.copyOf(diagnostics.values());
    }

    public CompensationDiagnostic getOrderDiagnostic(Long orderId) {
        return diagnostics.get(orderId);
    }

    public boolean reconcileOrder(Long orderId, String notes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() != OrderStatus.COMPENSATION_PENDING) {
            throw new BusinessException("Only orders in COMPENSATION_PENDING can be manually reconciled. Current status: " + order.getStatus());
        }

        orderStateService.markCancelled(orderId);
        recoveryAttempts.remove(orderId);
        diagnostics.remove(orderId);
        loggedExhaustedOrders.remove(orderId);

        if (eventMetrics != null) {
            eventMetrics.recordSagaCompensation("reconciled");
        }
        log.info("Operator manually reconciled Saga compensation for orderId={}: transitioned to CANCELLED, notes={}, service=order-service, outcome=SAGA_RECONCILED",
                orderId, notes);
        return true;
    }

    @Scheduled(fixedDelayString = "${saga.recovery.fixed-delay-ms:10000}")
    public int recoverPendingCompensations() {
        if (!enabled) {
            return 0;
        }

        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING);
        if (pendingOrders.isEmpty()) {
            return 0;
        }

        int recoveredCount = 0;

        for (Order order : pendingOrders) {
            int currentAttempts = recoveryAttempts.getOrDefault(order.getId(), 0);
            if (currentAttempts >= MAX_RECOVERY_ATTEMPTS) {
                // Log warning once to avoid polling spam every 10 seconds
                if (loggedExhaustedOrders.add(order.getId())) {
                    CompensationDiagnostic diag = diagnostics.get(order.getId());
                    String reason = (diag != null) ? diag.lastFailureReason() : "Max attempts reached";
                    String fType = (diag != null) ? diag.failureType() : "MAX_ATTEMPTS_EXCEEDED";
                    log.warn("Order {} has reached maximum compensation recovery attempts ({}/{}). failureType={}, lastReason='{}'. Requires operator manual compensation or reconciliation, service=order-service, outcome=MAX_ATTEMPTS_EXCEEDED",
                            order.getId(), currentAttempts, MAX_RECOVERY_ATTEMPTS, fType, reason);
                }
                continue;
            }

            recoveryAttempts.put(order.getId(), currentAttempts + 1);
            boolean orderRecovered = recoverOrderCompensation(order);
            if (orderRecovered) {
                recoveryAttempts.remove(order.getId());
                diagnostics.remove(order.getId());
                loggedExhaustedOrders.remove(order.getId());
                recoveredCount++;
            }
        }

        return recoveredCount;
    }

    private boolean recoverOrderCompensation(Order order) {
        log.info("Attempting Saga compensation recovery for orderId={}, service=order-service, outcome=SAGA_RECOVERY_ATTEMPTED",
                order.getId());
        boolean allItemsReleased = true;
        String failureReason = null;
        String failureType = "UNKNOWN";

        for (OrderItem item : order.getOrderItems()) {
            try {
                ProductClient.ProductApiResponse response =
                        productClient.releaseReservation(item.getProductId(), order.getId());

                if (response == null || !response.success()) {
                    log.warn("Downstream releaseReservation returned unsuccessful for orderId={}, productId={}, service=order-service, outcome=RELEASE_FAILED",
                            order.getId(), item.getProductId());
                    allItemsReleased = false;
                    failureReason = "Unsuccessful release response from product service";
                    failureType = "DOWNSTREAM_UNSUCCESSFUL";
                }
            } catch (ResourceNotFoundException notFound) {
                // Idempotent: reservation was never created or already released
                log.info("Reservation not found during recovery for orderId={}, productId={}, service=order-service, outcome=ALREADY_RELEASED",
                        order.getId(), item.getProductId());
            } catch (BusinessException bizEx) {
                // Permanent business conflict (e.g. 409 "Only reserved inventory can be released")
                log.error("Permanent business rule conflict during compensation for orderId={}, productId={}, service=order-service, error={}",
                        order.getId(), item.getProductId(), bizEx.getMessage());
                allItemsReleased = false;
                failureReason = bizEx.getMessage();
                failureType = "PERMANENT_BUSINESS_CONFLICT";
                // Fast-fail to max attempts to prevent useless repeated calls
                recoveryAttempts.put(order.getId(), MAX_RECOVERY_ATTEMPTS);
                break;
            } catch (Exception ex) {
                log.error("Failed to release reservation during recovery for orderId={}, productId={}, service=order-service, error={}",
                        order.getId(), item.getProductId(), ex.getMessage());
                allItemsReleased = false;
                failureReason = ex.getMessage();
                failureType = "DOWNSTREAM_UNAVAILABLE";
            }
        }

        if (allItemsReleased) {
            try {
                orderStateService.markCancelled(order.getId());
                if (eventMetrics != null) {
                    eventMetrics.recordSagaCompensation("recovered");
                }
                log.info("Successfully recovered Saga for orderId={}: transitioned to CANCELLED with ORDER_CANCELLED event, service=order-service, outcome=SAGA_RECOVERY_SUCCESS",
                        order.getId());
                return true;
            } catch (Exception ex) {
                if (eventMetrics != null) {
                    eventMetrics.recordSagaCompensation("failed");
                }
                log.error("Failed to mark order CANCELLED after releasing reservations for orderId={}, service=order-service, error={}",
                        order.getId(), ex.getMessage());
                recordDiagnostic(order, "STATE_TRANSITION_FAILED", ex.getMessage(), true);
                return false;
            }
        } else {
            if (eventMetrics != null) {
                eventMetrics.recordSagaCompensation("failed");
            }
            int attempts = recoveryAttempts.getOrDefault(order.getId(), 1);
            boolean isExhausted = (attempts >= MAX_RECOVERY_ATTEMPTS) || "PERMANENT_BUSINESS_CONFLICT".equals(failureType);
            recordDiagnostic(order, failureType, failureReason, isExhausted);

            if (isExhausted && loggedExhaustedOrders.add(order.getId())) {
                log.warn("Order {} entered exhausted compensation state ({}/{}). failureType={}, reason='{}'. Requires operator manual reconciliation, service=order-service, outcome=COMPENSATION_EXHAUSTED",
                        order.getId(), attempts, MAX_RECOVERY_ATTEMPTS, failureType, failureReason);
            }

            log.warn("Saga compensation recovery incomplete for orderId={}: remains in COMPENSATION_PENDING, attempts={}/{}, service=order-service, outcome=SAGA_RECOVERY_INCOMPLETE",
                    order.getId(), attempts, MAX_RECOVERY_ATTEMPTS);
            return false;
        }
    }

    private void recordDiagnostic(Order order, String failureType, String reason, boolean requiresIntervention) {
        Instant now = Instant.now();
        int attempts = recoveryAttempts.getOrDefault(order.getId(), 1);
        CompensationDiagnostic existing = diagnostics.get(order.getId());
        Instant firstAttempt = (existing != null) ? existing.firstAttemptAt() : now;

        diagnostics.put(order.getId(), new CompensationDiagnostic(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                attempts,
                MAX_RECOVERY_ATTEMPTS,
                failureType,
                reason != null ? reason : "Unknown failure",
                firstAttempt,
                now,
                requiresIntervention
        ));
    }
}
