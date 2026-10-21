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
}
