package com.blackroth.training.orderservice.service;

import com.blackroth.training.orderservice.client.*;
import com.blackroth.training.orderservice.dto.*;
import com.blackroth.training.orderservice.model.Order;
import com.blackroth.training.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    @Mock
    OrderRepository orders;
    @Mock
    ProductClient products;
    @InjectMocks
    OrderService service;

    OrderServiceTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void createsOrderUsingProductService() {
        var product = new ProductResponse(1L, "Phone", "", new BigDecimal("100.00"), 10, "Electronics");
        when(products.getProduct(1L)).thenReturn(product);
        when(orders.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = service.create(new OrderRequest(5L, List.of(new OrderItemRequest(1L, 2))));
        assertEquals(new BigDecimal("200.00"), response.totalAmount());
        verify(products).getProduct(1L);
        verify(orders).save(any());
    }

    @Test
    void rejectsInsufficientStock() {
        var product = new ProductResponse(1L, "Phone", "", new BigDecimal("100.00"), 1, "Electronics");
        when(products.getProduct(1L)).thenReturn(product);
        assertThrows(com.blackroth.training.orderservice.exception.OrderExceptions.InsufficientStock.class,
                () -> service.create(new OrderRequest(5L, List.of(new OrderItemRequest(1L, 2)))));
        verify(orders, never()).save(any(Order.class));
    }
}
