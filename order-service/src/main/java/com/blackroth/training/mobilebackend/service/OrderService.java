package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderItemResponse;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.event.OrderEventProducer;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderItem;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final OrderEventProducer orderEventProducer;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OrderEventProducer orderEventProducer
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.orderEventProducer = orderEventProducer;
    }

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {

        Long userId = (Long) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        Order order = new Order(
                userId,
                OrderStatus.PENDING,
                BigDecimal.ZERO
        );

        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {

            ProductClient.ProductResponse product;

            try {
                ProductClient.ProductApiResponse response =
                        productClient.getProduct(itemRequest.getProductId());

                product = response != null ? response.data() : null;

            } catch (feign.FeignException.NotFound exception) {
                throw new ResourceNotFoundException(
                        "Product not found with id: "
                                + itemRequest.getProductId()
                );
            } catch (feign.FeignException exception) {
                throw new BusinessException(
                        "Product Service unavailable"
                );
            }

            if (product == null) {
                throw new ResourceNotFoundException(
                        "Product not found with id: "
                                + itemRequest.getProductId()
                );
            }

            if (product.price() == null || product.stockQuantity() == null) {
                throw new BusinessException(
                        "Product price or stock information is unavailable"
                );
            }

            if (itemRequest.getQuantity() == null
                    || itemRequest.getQuantity() <= 0) {
                throw new BusinessException(
                        "Order quantity must be greater than zero"
                );
            }

            if (product.stockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException(
                        "Insufficient stock for product: " + product.name()
                );
            }

            try {
                ProductClient.ProductApiResponse stockResponse =
                        productClient.decreaseStock(
                                itemRequest.getProductId(),
                                itemRequest.getQuantity()
                        );

                if (stockResponse == null || !stockResponse.success()) {
                    throw new BusinessException(
                            "Product stock update failed"
                    );
                }

            } catch (feign.FeignException exception) {
                throw new BusinessException(
                        "Product stock update failed"
                );
            }

            OrderItem orderItem = new OrderItem(
                    product.id(),
                    product.name(),
                    itemRequest.getQuantity(),
                    product.price()
            );

            order.addOrderItem(orderItem);

            total = total.add(
                    product.price().multiply(
                            BigDecimal.valueOf(itemRequest.getQuantity())
                    )
            );
        }

        order.setTotalAmount(total);

        Order savedOrder = orderRepository.save(order);

        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                "OrderCreated",
                savedOrder.getId(),
                savedOrder.getUserId(),
                savedOrder.getTotalAmount(),
                Instant.now()
        );

        orderEventProducer.publishOrderCreated(event);

        return toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + id
                        )
                );

        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByUser(Long userId) {

        return orderRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public OrderResponse cancelOrder(Long id) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + id
                        )
                );

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Order is already cancelled");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(
                    "Only pending orders can be cancelled"
            );
        }

        order.setStatus(OrderStatus.CANCELLED);

        Order cancelledOrder = orderRepository.save(order);

        return toResponse(cancelledOrder);
    }

    private OrderResponse toResponse(Order order) {

        List<OrderItemResponse> items = order.getOrderItems()
                .stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(
                                BigDecimal.valueOf(item.getQuantity())
                        )
                ))
                .collect(Collectors.toList());

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                items
        );
    }
}