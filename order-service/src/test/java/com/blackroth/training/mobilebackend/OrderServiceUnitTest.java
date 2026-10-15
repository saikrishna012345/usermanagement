
package com.blackroth.training.mobilebackend;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.PaymentAuthorizationService;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import java.lang.reflect.Field;
import org.mockito.Mockito;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceUnitTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenProductStockIsInsufficient() {

        OrderStateService orderStateService =
                mock(OrderStateService.class);

        PaymentAuthorizationService paymentAuthorizationService =
                mock(PaymentAuthorizationService.class);

        OrderRepository orderRepository =
                mock(OrderRepository.class);

        ProductClient productClient =
                mock(ProductClient.class);

        OutboxEventRepository outboxRepository =
                mock(OutboxEventRepository.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        ProductClient.ProductResponse product =
                new ProductClient.ProductResponse(
                        1L,
                        "Phone",
                        "",
                        BigDecimal.TEN,
                        0,
                        "Electronics"
                );

        ProductClient.ProductApiResponse response =
                new ProductClient.ProductApiResponse(
                        true,
                        null,
                        "Product fetched successfully",
                        product,
                        null,
                        null
                );

        when(productClient.getProduct(1L))
                .thenReturn(response);

        OrderService service = new OrderService(
                orderRepository,
                productClient,
                outboxRepository,
                objectMapper,
                orderStateService,
                paymentAuthorizationService
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L,
                        null,
                        List.of()
                )
        );

        OrderRequest request = new OrderRequest();

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);

        request.setItems(List.of(item));

        assertThrows(
                BusinessException.class,
                () -> service.createOrder(request)
        );
    }

    @Test
    void cancelsOrderAndReleasesInventoryWhenPaymentFails() throws Exception {
        OrderStateService orderStateService = mock(OrderStateService.class);
        PaymentAuthorizationService paymentService =
                mock(PaymentAuthorizationService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductClient productClient = mock(ProductClient.class);
        OutboxEventRepository outboxRepository =
                mock(OutboxEventRepository.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        ProductClient.ProductResponse product =
                new ProductClient.ProductResponse(
                        1L, "Phone", "", BigDecimal.TEN, 10, "Electronics");

        ProductClient.ProductApiResponse productResponse =
                new ProductClient.ProductApiResponse(
                        true, 200, "Success", product, null, null);

        ProductClient.ProductApiResponse reservationResponse =
                new ProductClient.ProductApiResponse(
                        true, 200, "Reserved", product, null, null);

        ProductClient.ProductApiResponse releaseResponse =
                new ProductClient.ProductApiResponse(
                        true, 200, "Released", product, null, null);

        when(productClient.getProduct(1L)).thenReturn(productResponse);
        when(productClient.reserveStock(anyLong(), anyLong(), anyInt()))
                .thenReturn(reservationResponse);
        when(productClient.releaseReservation(anyLong(), anyLong()))
                .thenReturn(releaseResponse);

        Order pendingOrder = new Order();
        Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(pendingOrder, 100L);

        when(orderStateService.savePendingOrder(any(Order.class)))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    idField.set(order, 100L);
                    return order;
                });

        when(orderStateService.markCancelled(100L))
                .thenReturn(pendingOrder);

        doThrow(new BusinessException("Simulated payment failure"))
                .when(paymentService).authorize(100L);

        OrderService service = new OrderService(
                orderRepository,
                productClient,
                outboxRepository,
                objectMapper,
                orderStateService,
                paymentService
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of()));

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createOrder(request)
        );

        System.out.println("Saga test failure: " + exception.getMessage());

        verify(productClient).reserveStock(1L, 100L, 1);
        verify(paymentService).authorize(100L);
        verify(productClient).releaseReservation(1L, 100L);
        verify(orderStateService).markCancelled(100L);
    }

    @Test
    void marksCompensationPendingWhenDownstreamReleaseFails() throws Exception {
        OrderStateService orderStateService = mock(OrderStateService.class);
        PaymentAuthorizationService paymentService = mock(PaymentAuthorizationService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductClient productClient = mock(ProductClient.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        ProductClient.ProductResponse product =
                new ProductClient.ProductResponse(1L, "Phone", "", BigDecimal.TEN, 10, "Electronics");
        ProductClient.ProductApiResponse productResponse =
                new ProductClient.ProductApiResponse(true, 200, "Success", product, null, null);
        ProductClient.ProductApiResponse reservationResponse =
                new ProductClient.ProductApiResponse(true, 200, "Reserved", product, null, null);

        when(productClient.getProduct(1L)).thenReturn(productResponse);
        when(productClient.reserveStock(anyLong(), anyLong(), anyInt())).thenReturn(reservationResponse);
        // Downstream release times out or fails
        doThrow(new RuntimeException("Product service downstream timeout during release"))
                .when(productClient).releaseReservation(anyLong(), anyLong());

        Order pendingOrder = new Order();
        Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(pendingOrder, 200L);

        when(orderStateService.savePendingOrder(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            idField.set(o, 200L);
            return o;
        });

        doThrow(new BusinessException("Payment failed")).when(paymentService).authorize(200L);

        OrderService service = new OrderService(
                orderRepository, productClient, outboxRepository, objectMapper, orderStateService, paymentService
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createOrder(request));
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("compensation is pending"));

        verify(orderStateService).markCompensationPending(200L);
        verify(orderStateService, never()).markCancelled(200L);
    }

    @Test
    void marksCancelledWhenItemWasNeverReservedDuringCompensation() throws Exception {
        OrderStateService orderStateService = mock(OrderStateService.class);
        PaymentAuthorizationService paymentService = mock(PaymentAuthorizationService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductClient productClient = mock(ProductClient.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        ProductClient.ProductResponse product =
                new ProductClient.ProductResponse(1L, "Phone", "", BigDecimal.TEN, 10, "Electronics");
        ProductClient.ProductApiResponse productResponse =
                new ProductClient.ProductApiResponse(true, 200, "Success", product, null, null);

        when(productClient.getProduct(1L)).thenReturn(productResponse);
        // First reservation call throws BusinessException (e.g. stock exhausted during reserve)
        when(productClient.reserveStock(anyLong(), anyLong(), anyInt()))
                .thenThrow(new BusinessException("Out of stock"));
        // Release throws 404 ResourceNotFoundException because it was never reserved
        doThrow(new com.blackroth.training.mobilebackend.exception.ResourceNotFoundException("Reservation not found"))
                .when(productClient).releaseReservation(anyLong(), anyLong());

        Order pendingOrder = new Order();
        Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(pendingOrder, 300L);

        when(orderStateService.savePendingOrder(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            idField.set(o, 300L);
            return o;
        });

        OrderService service = new OrderService(
                orderRepository, productClient, outboxRepository, objectMapper, orderStateService, paymentService
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> service.createOrder(request));

        // When 404 occurs, it is recognized as no stock held, so compensation succeeded -> CANCELLED
        verify(orderStateService).markCancelled(300L);
        verify(orderStateService, never()).markCompensationPending(300L);
    }
}
