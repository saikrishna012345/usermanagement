
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
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.PaymentAuthorizationService;

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
    private final OrderStateService orderStateService;
    private final PaymentAuthorizationService paymentAuthorizationService;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            OrderStateService orderStateService,
            PaymentAuthorizationService paymentAuthorizationService
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.orderStateService = orderStateService;
        this.paymentAuthorizationService = paymentAuthorizationService;
    }



    public OrderResponse createOrder(OrderRequest request) {

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null
                || authentication.getPrincipal() == null
                || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new BusinessException("Authenticated user ID must be a Long");
        }

        if (request == null
                || request.getItems() == null
                || request.getItems().isEmpty()) {
            throw new BusinessException("An order must contain at least one item");
        }

        Order order = new Order(userId, OrderStatus.PENDING, BigDecimal.ZERO);
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<Long> productIds = new java.util.HashSet<>();

        // Validate items and build the order without changing remote inventory.
        for (OrderItemRequest itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new BusinessException("Product ID is required");
            }

            if (itemRequest.getQuantity() == null
                    || itemRequest.getQuantity() <= 0) {
                throw new BusinessException("Order quantity must be greater than zero");
            }

            if (!productIds.add(itemRequest.getProductId())) {
                throw new BusinessException(
                        "Duplicate product IDs are not allowed in one order");
            }

            ProductClient.ProductResponse product;
            try {
                ProductClient.ProductApiResponse response =
                        productClient.getProduct(itemRequest.getProductId());

                if (response == null || !response.success() || response.data() == null) {
                    throw new BusinessException("Unable to retrieve product details");
                }
                product = response.data();
            } catch (FeignException.NotFound exception) {
                throw new ResourceNotFoundException(
                        "Product not found with id: " + itemRequest.getProductId());
            } catch (FeignException exception) {
                throw new BusinessException("Product Service unavailable");
            }

            if (product.price() == null || product.stockQuantity() == null) {
                throw new BusinessException(
                        "Product price or stock information is unavailable");
            }

            if (product.price().signum() < 0) {
                throw new BusinessException("Product price cannot be negative");
            }

            if (product.stockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException(
                        "Insufficient stock for product: " + product.name());
            }

            order.addOrderItem(new OrderItem(
                    product.id(),
                    product.name(),
                    itemRequest.getQuantity(),
                    product.price()));

            total = total.add(product.price().multiply(
                    BigDecimal.valueOf(itemRequest.getQuantity())));
        }

        order.setTotalAmount(total);

        // Save PENDING in its own transaction to generate the order ID.
        Order savedOrder = orderStateService.savePendingOrder(order);
        java.util.List<OrderItem> reservedItems = new java.util.ArrayList<>();

        try {
            // Reserve inventory for each item using the generated order ID.
            for (OrderItem item : savedOrder.getOrderItems()) {
                ProductClient.ProductApiResponse reservation =
                        productClient.reserveStock(
                                item.getProductId(),
                                savedOrder.getId(),
                                item.getQuantity());

                if (reservation == null || !reservation.success()) {
                    throw new BusinessException(
                            "Inventory reservation failed for product: "
                                    + item.getProductId());
                }

                reservedItems.add(item);
            }

            // Simulated payment authorization; it can be configured to fail.
            paymentAuthorizationService.authorize(savedOrder.getId());

            // Commit each inventory reservation after payment authorization.
            for (OrderItem item : reservedItems) {
                ProductClient.ProductApiResponse commitResponse =
                        productClient.commitReservation(
                                item.getProductId(), savedOrder.getId());

                if (commitResponse == null || !commitResponse.success()) {
                    throw new BusinessException(
                            "Inventory commit failed for product: "
                                    + item.getProductId());
                }
            }

            Order confirmedOrder =
                    orderStateService.markConfirmed(savedOrder.getId());

            saveOutboxEvent(
                    confirmedOrder, "ORDER_CREATED", ORDER_CREATED_TOPIC);

            return toResponse(confirmedOrder);

        } catch (Exception failure) {
            boolean compensationSucceeded = true;

            // Release every reservation known to have succeeded.
            for (OrderItem item : reservedItems) {
                try {
                    ProductClient.ProductApiResponse release =
                            productClient.releaseReservation(
                                    item.getProductId(), savedOrder.getId());

                    if (release == null || !release.success()) {
                        compensationSucceeded = false;
                    }
                } catch (Exception compensationFailure) {
                    compensationSucceeded = false;
                }
            }

            if (compensationSucceeded) {
                Order cancelledOrder =
                        orderStateService.markCancelled(savedOrder.getId());

                saveOutboxEvent(
                        cancelledOrder, "ORDER_CANCELLED", ORDER_CANCELLED_TOPIC);
            } else {
                orderStateService.markCompensationPending(savedOrder.getId());
            }

            throw new BusinessException(
                    "Order Saga failed for order " + savedOrder.getId()
                            + (compensationSucceeded
                            ? "; inventory compensation completed"
                            : "; compensation is pending and requires recovery"));
        }
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
