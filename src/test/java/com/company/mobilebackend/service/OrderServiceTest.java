package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.OrderItemRequest;
import com.company.mobilebackend.dto.OrderRequest;
import com.company.mobilebackend.dto.OrderResponse;
import com.company.mobilebackend.exception.BusinessException;
import com.company.mobilebackend.exception.ResourceNotFoundException;
import com.company.mobilebackend.model.Category;
import com.company.mobilebackend.model.Order;
import com.company.mobilebackend.model.OrderStatus;
import com.company.mobilebackend.model.Product;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.OrderRepository;
import com.company.mobilebackend.repository.ProductRepository;
import com.company.mobilebackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderService orderService;

    private User user;
    private Product product;
    private Order order;

    @BeforeEach
    void setUp() {
        user = new User("Sai", "Krishna", "sai@example.com", "9876543210", "secret123", "ACTIVE");
        user.setId(1L);

        Category category = new Category("Electronics");
        category.setId(1L);

        product = new Product("Phone", "A smartphone", BigDecimal.valueOf(500), 10, category);
        product.setId(1L);

        order = new Order(user, OrderStatus.PENDING, BigDecimal.valueOf(1000));
        order.setId(1L);
    }

    @Test
    void createOrder_succeeds_andReducesStock_whenEnoughStockAvailable() {
        OrderRequest request = new OrderRequest();
        request.setUserId(1L);
        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(1L);
        itemRequest.setQuantity(2);
        request.setItems(List.of(itemRequest));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(product.getStockQuantity()).isEqualTo(8);
        verify(productRepository, times(1)).save(product);
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    void createOrder_throwsResourceNotFoundException_whenUserInvalid() {
        OrderRequest request = new OrderRequest();
        request.setUserId(99L);
        request.setItems(List.of());

        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.createOrder(request));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_throwsResourceNotFoundException_whenProductInvalid() {
        OrderRequest request = new OrderRequest();
        request.setUserId(1L);
        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(99L);
        itemRequest.setQuantity(1);
        request.setItems(List.of(itemRequest));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.createOrder(request));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_throwsBusinessException_whenQuantityExceedsStock() {
        OrderRequest request = new OrderRequest();
        request.setUserId(1L);
        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(1L);
        itemRequest.setQuantity(999);
        request.setItems(List.of(itemRequest));

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(BusinessException.class, () -> orderService.createOrder(request));

        // Rollback proof at the business-logic level: stock must remain unchanged
        // and the order must never have been persisted.
        assertThat(product.getStockQuantity()).isEqualTo(10);
        verify(productRepository, never()).save(any(Product.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void cancelOrder_restoresStock_andSetsStatusCancelled() {
        order.addOrderItem(new com.company.mobilebackend.model.OrderItem(product, 2, BigDecimal.valueOf(500)));
        product.setStockQuantity(8);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderResponse response = orderService.cancelOrder(1L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getStockQuantity()).isEqualTo(10);
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void cancelOrder_throwsBusinessException_whenAlreadyCancelled() {
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(1L));
    }

    @Test
    void cancelOrder_throwsResourceNotFoundException_whenOrderMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.cancelOrder(99L));
    }
}