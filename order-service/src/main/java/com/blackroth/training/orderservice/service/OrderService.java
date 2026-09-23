package com.blackroth.training.orderservice.service;

import com.blackroth.training.orderservice.client.ProductClient;
import com.blackroth.training.orderservice.client.ProductResponse;
import com.blackroth.training.orderservice.dto.*;
import com.blackroth.training.orderservice.exception.OrderExceptions.*;
import com.blackroth.training.orderservice.model.*;
import com.blackroth.training.orderservice.repository.OrderRepository;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final ProductClient products;

    public OrderService(OrderRepository orders, ProductClient products) {
        this.orders = orders;
        this.products = products;
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = new Order(request.userId(), OrderStatus.PENDING, BigDecimal.ZERO);
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest item : request.items()) {
            ProductResponse product = getProduct(item.productId());
            if (product.stockQuantity() == null || product.stockQuantity() < item.quantity()) {
                throw new InsufficientStock("Insufficient stock for product: " + product.name());
            }

            OrderItem orderItem = new OrderItem(
                    product.id(), product.name(), item.quantity(), product.price());
            order.addItem(orderItem);
            total = total.add(product.price().multiply(BigDecimal.valueOf(item.quantity())));
        }

        order.setTotalAmount(total);
        return toResponse(orders.save(order));
    }

    private ProductResponse getProduct(Long id) {
        try {
            return products.getProduct(id);
        } catch (ProductNotFound e) {
            throw e;
        } catch (RetryableException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("timeout") || message.contains("timed out")) {
                throw new ProductServiceTimeout("Product Service timeout", e);
            }
            throw new ProductServiceUnavailable("Product Service connection failed", e);
        } catch (FeignException e) {
            if (e.status() == 404) {
                throw new ProductNotFound("Product not found with id: " + id);
            }
            if (e.status() >= 500 || e.status() == -1) {
                throw new ProductServiceUnavailable("Product Service unavailable", e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orders.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new OrderNotFound("Order not found with id: " + id));
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), order.getOrderItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(), item.getProductName(), item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList());
    }
}
