package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.client.ProductClient;
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

import java.util.List;

/**
 * Recovers orders whose downstream Saga compensation previously failed or timed out.
 * Ensures data consistency across application restarts by querying persisted
 * COMPENSATION_PENDING orders and re-executing idempotent inventory releases.
 */
@Service
public class SagaCompensationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SagaCompensationRecoveryService.class);

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

    @Scheduled(fixedDelayString = "${saga.recovery.fixed-delay-ms:10000}")
    public int recoverPendingCompensations() {
        if (!enabled) {
            return 0;
        }

        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING);
        if (pendingOrders.isEmpty()) {
            return 0;
        }

        log.info("Found {} orders in COMPENSATION_PENDING requiring Saga compensation recovery, service=order-service, outcome=SAGA_RECOVERY_STARTED",
                pendingOrders.size());
        int recoveredCount = 0;

        for (Order order : pendingOrders) {
            boolean orderRecovered = recoverOrderCompensation(order);
            if (orderRecovered) {
                recoveredCount++;
            }
        }

        return recoveredCount;
    }

    private boolean recoverOrderCompensation(Order order) {
        log.info("Attempting Saga compensation recovery for orderId={}, service=order-service, outcome=SAGA_RECOVERY_ATTEMPTED",
                order.getId());
        boolean allItemsReleased = true;

        for (OrderItem item : order.getOrderItems()) {
            try {
                ProductClient.ProductApiResponse response =
                        productClient.releaseReservation(item.getProductId(), order.getId());

                if (response == null || !response.success()) {
                    log.warn("Downstream releaseReservation returned unsuccessful for orderId={}, productId={}, service=order-service, outcome=RELEASE_FAILED",
                            order.getId(), item.getProductId());
                    allItemsReleased = false;
                }
            } catch (ResourceNotFoundException notFound) {
                // Idempotent: reservation was never created or already released
                log.info("Reservation not found during recovery for orderId={}, productId={}, service=order-service, outcome=ALREADY_RELEASED",
                        order.getId(), item.getProductId());
            } catch (Exception ex) {
                log.error("Failed to release reservation during recovery for orderId={}, productId={}, service=order-service, error={}",
                        order.getId(), item.getProductId(), ex.getMessage());
                allItemsReleased = false;
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
                return false;
            }
        } else {
            if (eventMetrics != null) {
                eventMetrics.recordSagaCompensation("failed");
            }
            log.warn("Saga compensation recovery incomplete for orderId={}: remains in COMPENSATION_PENDING, service=order-service, outcome=SAGA_RECOVERY_INCOMPLETE",
                    order.getId());
            return false;
        }
    }
}
