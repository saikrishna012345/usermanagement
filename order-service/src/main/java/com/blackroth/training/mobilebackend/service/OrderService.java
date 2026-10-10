
package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderItemResponse;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderItem;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.springframework.security.core.Authentication;
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

    private static final String ORDER_CREATED_TOPIC = "order.created";
    private static final String ORDER_CANCELLED_TOPIC = "order.cancelled";

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null
                || authentication.getPrincipal() == null) {
            throw new BusinessException("Authenticated user not found");
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Long userId)) {
            throw new BusinessException(
                    "Authenticated user ID must be a Long"
            );
        }

        if (request == null
                || request.getItems() == null
                || request.getItems().isEmpty()) {
            throw new BusinessException(
                    "An order must contain at least one item"
            );
        }

        Order order = new Order(
                userId,
                OrderStatus.PENDING,
                BigDecimal.ZERO
        );

        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {

            if (itemRequest == null
                    || itemRequest.getProductId() == null) {
                throw new BusinessException(
                        "Product ID is required"
                );
            }

            if (itemRequest.getQuantity() == null
                    || itemRequest.getQuantity() <= 0) {
                throw new BusinessException(
                        "Order quantity must be greater than zero"
                );
            }

            ProductClient.ProductResponse product;

            try {
                ProductClient.ProductApiResponse response =
                        productClient.getProduct(
                                itemRequest.getProductId()
                        );

                if (response == null || !response.success()) {
                    throw new BusinessException(
                            "Unable to retrieve product details"
                    );
                }

                product = response.data();

            } catch (FeignException.NotFound exception) {
                throw new ResourceNotFoundException(
                        "Product not found with id: "
                                + itemRequest.getProductId()
                );
            } catch (FeignException exception) {
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

            if (product.price() == null
                    || product.stockQuantity() == null) {
                throw new BusinessException(
                        "Product price or stock information is unavailable"
                );
            }

            if (product.price().signum() < 0) {
                throw new BusinessException(
                        "Product price cannot be negative"
                );
            }

            if (product.stockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException(
                        "Insufficient stock for product: "
                                + product.name()
                );
            }

            try {
                ProductClient.ProductApiResponse stockResponse =
                        productClient.decreaseStock(
                                itemRequest.getProductId(),
                                itemRequest.getQuantity()
                        );

                if (stockResponse == null
                        || !stockResponse.success()) {
                    throw new BusinessException(
                            "Product stock update failed"
                    );
                }

            } catch (FeignException exception) {
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

            BigDecimal itemTotal = product.price().multiply(
                    BigDecimal.valueOf(itemRequest.getQuantity())
            );

            total = total.add(itemTotal);
        }

        order.setTotalAmount(total);

        // Save the order first so its generated ID is available.
        Order savedOrder = orderRepository.saveAndFlush(order);

        // Store the event in the same database transaction.
        saveOutboxEvent(
                savedOrder,
                "ORDER_CREATED",
                ORDER_CREATED_TOPIC
        );

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
            throw new BusinessException(
                    "Order is already cancelled"
            );
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(
                    "Only pending orders can be cancelled"
            );
        }

        order.setStatus(OrderStatus.CANCELLED);

        Order cancelledOrder = orderRepository.saveAndFlush(order);

        saveOutboxEvent(
                cancelledOrder,
                "ORDER_CANCELLED",
                ORDER_CANCELLED_TOPIC
        );

        return toResponse(cancelledOrder);
    }

    private void saveOutboxEvent(
            Order order,
            String eventType,
            String topic
    ) {
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                eventType,
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                Instant.now()
        );

        try {
            OutboxEvent outboxEvent = new OutboxEvent();

            outboxEvent.setEventId(event.eventId());
            outboxEvent.setEventType(eventType);
            outboxEvent.setTopic(topic);
            outboxEvent.setAggregateId(
                    order.getId().toString()
            );
            outboxEvent.setPayload(
                    objectMapper.writeValueAsString(event)
            );
            outboxEvent.setStatus("PENDING");

            outboxEventRepository.save(outboxEvent);

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize " + eventType + " event",
                    exception
            );
        }
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
