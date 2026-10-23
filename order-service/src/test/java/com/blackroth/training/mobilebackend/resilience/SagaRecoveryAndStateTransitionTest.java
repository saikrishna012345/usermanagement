package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderItem;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.SagaCompensationRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Saga Recovery, State Transitions, Late Events, and Idempotent Compensation Tests")
class SagaRecoveryAndStateTransitionTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProductClient productClient;

    private ObjectMapper objectMapper;
    private OrderStateService orderStateService;
    private SagaCompensationRecoveryService recoveryService;

    private static final Long ORDER_ID = 901L;
    private static final Long USER_ID = 77L;
    private static final Long PRODUCT_ID = 55L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        orderStateService = new OrderStateService(orderRepository, outboxEventRepository, objectMapper);
        recoveryService = new SagaCompensationRecoveryService(orderRepository, productClient, orderStateService);
        ReflectionTestUtils.setField(recoveryService, "enabled", true);
    }

    private Order createTestOrder(OrderStatus status) {
        Order order = new Order(USER_ID, status, new BigDecimal("149.99"));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.addOrderItem(new OrderItem(PRODUCT_ID, "Tablet", 1, new BigDecimal("149.99")));
        return order;
    }

    @Test
    @DisplayName("Restart Recovery: Recovers COMPENSATION_PENDING order by releasing remaining reservations and marking CANCELLED")
    void testRestartRecoveryForCompensationPendingOrder() {
        Order pendingCompensationOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);

        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingCompensationOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingCompensationOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductClient.ProductApiResponse releaseSuccess =
                new ProductClient.ProductApiResponse(true, 200, "Released", null, null, "2026-10-21T10:00:00");
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenReturn(releaseSuccess);

        int recoveredCount = recoveryService.recoverPendingCompensations();

        assertEquals(1, recoveredCount);
        assertEquals(OrderStatus.CANCELLED, pendingCompensationOrder.getStatus());
        verify(productClient).releaseReservation(PRODUCT_ID, ORDER_ID);
        verify(orderRepository).saveAndFlush(pendingCompensationOrder);

        // Verifies ORDER_CANCELLED event is generated upon successful recovery
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertEquals("ORDER_CANCELLED", outboxCaptor.getValue().getEventType());
        assertEquals("order.cancelled", outboxCaptor.getValue().getTopic());
        assertEquals(String.valueOf(ORDER_ID), outboxCaptor.getValue().getAggregateId());
    }

    @Test
    @DisplayName("Restart Recovery: Downstream release failure keeps order in COMPENSATION_PENDING for next poll")
    void testRecoveryFailureKeepsCompensationPending() {
        Order pendingCompensationOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);

        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingCompensationOrder));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ProductServiceUnavailableException("Product Service connection timeout"));

        int recoveredCount = recoveryService.recoverPendingCompensations();

        assertEquals(0, recoveredCount);
        assertEquals(OrderStatus.COMPENSATION_PENDING, pendingCompensationOrder.getStatus());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotent Compensation: Repeated markCancelled does not create duplicate outbox events or corrupt state")
    void testRepeatedCompensationIdempotency() {
        Order cancelledOrder = createTestOrder(OrderStatus.CANCELLED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(cancelledOrder));

        // Call markCancelled on already CANCELLED order
        Order result = orderStateService.markCancelled(ORDER_ID);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        // Verify no duplicate outbox event was created
        verify(outboxEventRepository, never()).save(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Late Event: Confirmation attempt after order was CANCELLED is rejected with BusinessException")
    void testLateConfirmationAfterCancellationRejected() {
        Order cancelledOrder = createTestOrder(OrderStatus.CANCELLED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(cancelledOrder));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orderStateService.markConfirmed(ORDER_ID));

        assertTrue(ex.getMessage().contains("Illegal state transition: cannot confirm order with status CANCELLED"));
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Late Event: markCompensationPending on already CANCELLED order is safe no-op")
    void testLateFailureAfterCancellationIsSafeNoOp() {
        Order cancelledOrder = createTestOrder(OrderStatus.CANCELLED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(cancelledOrder));

        Order result = orderStateService.markCompensationPending(ORDER_ID);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Invalid State Transitions: Attempting illegal transitions throws BusinessException")
    void testIllegalStateTransitions() {
        // 1. Cannot ship CANCELLED order
        Order cancelledOrder = createTestOrder(OrderStatus.CANCELLED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(cancelledOrder));
        assertThrows(BusinessException.class, () -> orderStateService.markShipped(ORDER_ID));

        // 2. Cannot ship PENDING order
        Order pendingOrder = createTestOrder(OrderStatus.PENDING);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder));
        assertThrows(BusinessException.class, () -> orderStateService.markShipped(ORDER_ID));

        // 3. Cannot deliver PENDING or CONFIRMED order
        Order confirmedOrder = createTestOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(confirmedOrder));
        assertThrows(BusinessException.class, () -> orderStateService.markDelivered(ORDER_ID));

        // 4. Cannot cancel CONFIRMED order directly via state service
        assertThrows(BusinessException.class, () -> orderStateService.markCancelled(ORDER_ID));
    }

    @Test
    @DisplayName("Disabled recovery service skips processing")
    void testDisabledRecoveryService() {
        ReflectionTestUtils.setField(recoveryService, "enabled", false);
        int recovered = recoveryService.recoverPendingCompensations();
        assertEquals(0, recovered);
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(productClient);
    }

    @Test
    @DisplayName("Bounded Retries: Exceeding max recovery attempts skips downstream call and alerts operator")
    void testBoundedRetriesExceedingMaxAttemptsSkipsDownstream() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ProductServiceUnavailableException("Downstream timeout"));

        // Exhaust 5 attempts
        for (int i = 0; i < 5; i++) {
            recoveryService.recoverPendingCompensations();
        }

        assertEquals(5, recoveryService.getRecoveryAttempts(ORDER_ID));
        verify(productClient, times(5)).releaseReservation(PRODUCT_ID, ORDER_ID);

        // 6th attempt should be SKIPPED due to MAX_RECOVERY_ATTEMPTS = 5
        int recoveredCount = recoveryService.recoverPendingCompensations();
        assertEquals(0, recoveredCount);
        // Verify productClient was NOT called a 6th time
        verify(productClient, times(5)).releaseReservation(PRODUCT_ID, ORDER_ID);
    }

    @Test
    @DisplayName("Transient Failure: Downstream timeout on first attempt followed by successful retry recovers order")
    void testTransientDownstreamFailureFollowedBySuccessfulRetry() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Attempt 1: Transient 503, Attempt 2: 200 OK
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ProductServiceUnavailableException("Transient 503 Service Unavailable"))
                .thenReturn(new ProductClient.ProductApiResponse(true, 200, "Released", null, null, "2026-10-21T10:00:00"));

        int firstPollRecovered = recoveryService.recoverPendingCompensations();
        assertEquals(0, firstPollRecovered);
        assertEquals(1, recoveryService.getRecoveryAttempts(ORDER_ID));
        assertEquals(OrderStatus.COMPENSATION_PENDING, pendingOrder.getStatus());

        int secondPollRecovered = recoveryService.recoverPendingCompensations();
        assertEquals(1, secondPollRecovered);
        assertEquals(0, recoveryService.getRecoveryAttempts(ORDER_ID));
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
        verify(productClient, times(2)).releaseReservation(PRODUCT_ID, ORDER_ID);
        verify(orderRepository).saveAndFlush(pendingOrder);
    }

    @Test
    @DisplayName("Permanent Failure: 409 Conflict immediately fast-fails to limit and records diagnostic")
    void testPermanentFailureReachingRetryLimitFastFailsAndRecordsDiagnostic() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new BusinessException("Only reserved inventory can be released"));

        int recoveredCount = recoveryService.recoverPendingCompensations();
        assertEquals(0, recoveredCount);
        assertEquals(SagaCompensationRecoveryService.MAX_RECOVERY_ATTEMPTS, recoveryService.getRecoveryAttempts(ORDER_ID));

        SagaCompensationRecoveryService.CompensationDiagnostic diagnostic = recoveryService.getOrderDiagnostic(ORDER_ID);
        assertNotNull(diagnostic);
        assertEquals("PERMANENT_BUSINESS_CONFLICT", diagnostic.failureType());
        assertTrue(diagnostic.lastFailureReason().contains("Only reserved inventory can be released"));
        assertTrue(diagnostic.requiresManualIntervention());

        // Subsequent poll skips without calling productClient again
        recoveryService.recoverPendingCompensations();
        verify(productClient, times(1)).releaseReservation(PRODUCT_ID, ORDER_ID);
    }

    @Test
    @DisplayName("Dependency Recovery: Service unavailable for multiple polls recovers once dependency is restored")
    void testRecoveryAfterDependencyBecomesAvailable() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ProductServiceUnavailableException("Connection refused"))
                .thenThrow(new ProductServiceUnavailableException("Connection refused"))
                .thenReturn(new ProductClient.ProductApiResponse(true, 200, "Released", null, null, "2026-10-21T10:00:00"));

        assertEquals(0, recoveryService.recoverPendingCompensations());
        assertEquals(1, recoveryService.getRecoveryAttempts(ORDER_ID));

        assertEquals(0, recoveryService.recoverPendingCompensations());
        assertEquals(2, recoveryService.getRecoveryAttempts(ORDER_ID));

        // Dependency back online
        assertEquals(1, recoveryService.recoverPendingCompensations());
        assertEquals(0, recoveryService.getRecoveryAttempts(ORDER_ID));
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
    }

    @Test
    @DisplayName("Idempotent Compensation: Downstream ResourceNotFoundException is treated as already released and succeeds")
    void testIdempotentCompensationWhenDownstreamAlreadyReleased() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Downstream throws ResourceNotFoundException (reservation was already released or not found)
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ResourceNotFoundException("Reservation not found for order: " + ORDER_ID));

        int recoveredCount = recoveryService.recoverPendingCompensations();
        assertEquals(1, recoveredCount);
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
        verify(productClient).releaseReservation(PRODUCT_ID, ORDER_ID);
        verify(orderRepository).saveAndFlush(pendingOrder);
    }

    @Test
    @DisplayName("Concurrent Recovery: Multiple concurrent recovery runs safely process pending orders without corruption")
    void testConcurrentRecoveryAttemptsThreadSafety() throws Exception {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenReturn(new ProductClient.ProductApiResponse(true, 200, "Released", null, null, "2026-10-21T10:00:00"));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.Callable<Integer> task = () -> recoveryService.recoverPendingCompensations();

        java.util.List<java.util.concurrent.Future<Integer>> futures = executor.invokeAll(List.of(task, task, task, task));
        executor.shutdown();

        int totalRecovered = 0;
        for (java.util.concurrent.Future<Integer> f : futures) {
            totalRecovered += f.get();
        }

        assertTrue(totalRecovered >= 1);
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
    }

    @Test
    @DisplayName("Diagnostics and Reconciliation: Operator can inspect exhausted diagnostics and manually reconcile order")
    void testAccurateTerminalExhaustedStateAndOperatorVisibleDiagnostics() {
        Order pendingOrder = createTestOrder(OrderStatus.COMPENSATION_PENDING);
        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(pendingOrder));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(pendingOrder));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new BusinessException("Only reserved inventory can be released"));

        recoveryService.recoverPendingCompensations();

        List<SagaCompensationRecoveryService.CompensationDiagnostic> exhausted = recoveryService.getExhaustedDiagnostics();
        assertEquals(1, exhausted.size());
        assertEquals(ORDER_ID, exhausted.get(0).orderId());
        assertEquals(USER_ID, exhausted.get(0).userId());
        assertEquals("PERMANENT_BUSINESS_CONFLICT", exhausted.get(0).failureType());
        assertTrue(exhausted.get(0).requiresManualIntervention());

        // Operator manual reconciliation
        boolean reconciled = recoveryService.reconcileOrder(ORDER_ID, "Reconciled after warehouse stock audit");
        assertTrue(reconciled);
        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
        assertEquals(0, recoveryService.getExhaustedDiagnostics().size());
        assertNull(recoveryService.getOrderDiagnostic(ORDER_ID));
        assertEquals(0, recoveryService.getRecoveryAttempts(ORDER_ID));
    }
}
