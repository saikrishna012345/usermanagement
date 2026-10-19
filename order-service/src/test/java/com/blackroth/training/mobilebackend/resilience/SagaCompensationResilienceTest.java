package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.service.OrderService;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.PaymentAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Saga Orchestration, Inventory Compensation, and Consistency Chaos Tests")
class SagaCompensationResilienceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PaymentAuthorizationService paymentAuthorizationService;

    private ObjectMapper objectMapper;
    private OrderStateService orderStateService;
    private OrderService orderService;

    private static final Long USER_ID = 42L;
    private static final Long ORDER_ID = 501L;
    private static final Long PRODUCT_ID = 10L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Setup Spring Security Context with authenticated Long userId
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID, "N/A", Collections.emptyList());
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        orderStateService = spy(new OrderStateService(orderRepository, outboxEventRepository, objectMapper));
        orderService = new OrderService(
                orderRepository,
                productClient,
                outboxEventRepository,
                objectMapper,
                orderStateService,
                paymentAuthorizationService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProductClient.ProductApiResponse createMockProductResponse(Long id, BigDecimal price, int stock) {
        ProductClient.ProductResponse data = new ProductClient.ProductResponse(
                id, "Smartphone", "Latest model", price, stock, "Electronics"
        );
        return new ProductClient.ProductApiResponse(true, 200, "Success", data, null, "2026-10-19T10:00:00");
    }

    private ProductClient.ProductApiResponse createSuccessApiResponse() {
        return new ProductClient.ProductApiResponse(true, 200, "Operation Successful", null, null, "2026-10-19T10:00:00");
    }

    private OrderRequest createOrderRequest(Long productId, int quantity) {
        OrderRequest request = new OrderRequest();
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        request.setItems(List.of(item));
        return request;
    }

    @Test
    @DisplayName("Scenario 1: Happy Path -> Stock reserved, payment authorized, reservation committed, order CONFIRMED")
    void whenSagaSucceeds_thenOrderIsConfirmedAndCommitted() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 2);

        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 10));

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });
        when(orderRepository.findById(ORDER_ID)).thenAnswer(inv -> {
            Order o = new Order(USER_ID, OrderStatus.PENDING, new BigDecimal("200.00"));
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return Optional.of(o);
        });

        when(productClient.reserveStock(eq(PRODUCT_ID), eq(ORDER_ID), eq(2)))
                .thenReturn(createSuccessApiResponse());
        doNothing().when(paymentAuthorizationService).authorize(ORDER_ID);
        when(productClient.commitReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenReturn(createSuccessApiResponse());

        OrderResponse response = orderService.createOrder(request);

        assertNotNull(response);
        assertEquals(OrderStatus.CONFIRMED, response.getStatus());

        verify(productClient).reserveStock(PRODUCT_ID, ORDER_ID, 2);
        verify(paymentAuthorizationService).authorize(ORDER_ID);
        verify(productClient).commitReservation(PRODUCT_ID, ORDER_ID);
        verify(orderStateService).markConfirmed(ORDER_ID);

        // Verify ORDER_CREATED outbox event created
        verify(outboxEventRepository).save(argThat(event ->
                "ORDER_CREATED".equals(event.getEventType()) && "order.created".equals(event.getTopic())));
    }

    @Test
    @DisplayName("Scenario 2: Insufficient stock -> Fails before reservation, order not confirmed")
    void whenInsufficientStock_thenThrowsBusinessExceptionWithoutReservation() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 5);

        // Product only has 2 in stock
        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 2));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.createOrder(request));
        assertTrue(ex.getMessage().contains("Insufficient stock"));

        verify(productClient, never()).reserveStock(anyLong(), anyLong(), anyInt());
        verify(paymentAuthorizationService, never()).authorize(anyLong());
        verify(orderStateService, never()).markConfirmed(anyLong());
    }

    @Test
    @DisplayName("Scenario 3: Timeout during reservation -> Compensation releases reservations, order CANCELLED")
    void whenReservationTimesOut_thenCompensationReleasesAndOrderCancelled() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 2);

        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 10));

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });
        when(orderRepository.findById(ORDER_ID)).thenAnswer(inv -> {
            Order o = new Order(USER_ID, OrderStatus.PENDING, new BigDecimal("200.00"));
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return Optional.of(o);
        });

        // Simulating timeout during reservation
        when(productClient.reserveStock(eq(PRODUCT_ID), eq(ORDER_ID), eq(2)))
                .thenThrow(new ProductServiceUnavailableException("Product Service connection timeout"));

        // During compensation release
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenReturn(createSuccessApiResponse());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.createOrder(request));
        assertTrue(ex.getMessage().contains("Order Saga failed"));
        assertTrue(ex.getMessage().contains("inventory compensation completed"));

        // Compensation succeeded -> order marked CANCELLED
        verify(orderStateService).markCancelled(ORDER_ID);
        verify(orderStateService, never()).markCompensationPending(anyLong());
        verify(productClient).releaseReservation(PRODUCT_ID, ORDER_ID);
    }

    @Test
    @DisplayName("Scenario 4 & 5: Payment failure after reservation -> Compensation succeeds, order CANCELLED")
    void whenPaymentFails_thenCompensationReleasesAllReservationsAndOrderCancelled() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 2);

        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 10));

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });
        when(orderRepository.findById(ORDER_ID)).thenAnswer(inv -> {
            Order o = new Order(USER_ID, OrderStatus.PENDING, new BigDecimal("200.00"));
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return Optional.of(o);
        });

        when(productClient.reserveStock(eq(PRODUCT_ID), eq(ORDER_ID), eq(2)))
                .thenReturn(createSuccessApiResponse());

        // Payment authorization fails
        doThrow(new BusinessException("Insufficient funds on card"))
                .when(paymentAuthorizationService).authorize(ORDER_ID);

        // Compensation release succeeds
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenReturn(createSuccessApiResponse());

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.createOrder(request));
        assertTrue(ex.getMessage().contains("inventory compensation completed"));

        verify(productClient).reserveStock(PRODUCT_ID, ORDER_ID, 2);
        verify(productClient).releaseReservation(PRODUCT_ID, ORDER_ID);
        verify(orderStateService).markCancelled(ORDER_ID);

        // Verify ORDER_CANCELLED outbox event was generated
        verify(outboxEventRepository).save(argThat(event ->
                "ORDER_CANCELLED".equals(event.getEventType()) && "order.cancelled".equals(event.getTopic())));
    }

    @Test
    @DisplayName("Scenario 6: Compensation release fails -> Order transitions to COMPENSATION_PENDING")
    void whenCompensationReleaseFails_thenOrderTransitionsToCompensationPending() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 2);

        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 10));

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });
        when(orderRepository.findById(ORDER_ID)).thenAnswer(inv -> {
            Order o = new Order(USER_ID, OrderStatus.PENDING, new BigDecimal("200.00"));
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return Optional.of(o);
        });

        when(productClient.reserveStock(eq(PRODUCT_ID), eq(ORDER_ID), eq(2)))
                .thenReturn(createSuccessApiResponse());

        // Payment fails
        doThrow(new BusinessException("Payment gateway error"))
                .when(paymentAuthorizationService).authorize(ORDER_ID);

        // Compensation release FAILS (chaos: downstream service down)
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ProductServiceUnavailableException("Product Service 503 Unavailable during compensation"));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.createOrder(request));
        assertTrue(ex.getMessage().contains("compensation is pending and requires recovery"));

        // Critical consistency guarantee: Must enter COMPENSATION_PENDING, NOT CANCELLED!
        verify(orderStateService).markCompensationPending(ORDER_ID);
        verify(orderStateService, never()).markCancelled(anyLong());

        // And ORDER_CANCELLED outbox event MUST NOT be published yet
        verify(outboxEventRepository, never()).save(argThat(event ->
                "ORDER_CANCELLED".equals(event.getEventType())));
    }

    @Test
    @DisplayName("Scenario 7: Compensation ignores ResourceNotFoundException if item was never reserved")
    void whenCompensationReceives404_thenTreatedAsReleaseNotRequired() {
        OrderRequest request = createOrderRequest(PRODUCT_ID, 2);

        when(productClient.getProduct(PRODUCT_ID))
                .thenReturn(createMockProductResponse(PRODUCT_ID, new BigDecimal("100.00"), 10));

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return o;
        });
        when(orderRepository.findById(ORDER_ID)).thenAnswer(inv -> {
            Order o = new Order(USER_ID, OrderStatus.PENDING, new BigDecimal("200.00"));
            ReflectionTestUtils.setField(o, "id", ORDER_ID);
            return Optional.of(o);
        });

        // Reservation fails before creating record
        when(productClient.reserveStock(eq(PRODUCT_ID), eq(ORDER_ID), eq(2)))
                .thenThrow(new ProductServiceUnavailableException("Network glitch before reservation"));

        // Downstream reports 404 on release because no reservation exists
        when(productClient.releaseReservation(eq(PRODUCT_ID), eq(ORDER_ID)))
                .thenThrow(new ResourceNotFoundException("Reservation not found for order: " + ORDER_ID));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.createOrder(request));
        assertTrue(ex.getMessage().contains("inventory compensation completed"));

        // 404 is considered release not required -> marked CANCELLED
        verify(orderStateService).markCancelled(ORDER_ID);
    }
}
