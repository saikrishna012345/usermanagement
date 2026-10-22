
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderService.class);
    private static final String ORDER_CREATED_TOPIC = com.blackroth.training.mobilebackend.event.contract.TopicNames.ORDER_CREATED;
    private static final String ORDER_CANCELLED_TOPIC = com.blackroth.training.mobilebackend.event.contract.TopicNames.ORDER_CANCELLED;

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final OrderStateService orderStateService;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final com.blackroth.training.mobilebackend.metrics.EventMetrics eventMetrics;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            OrderStateService orderStateService,
            PaymentAuthorizationService paymentAuthorizationService
    ) {
        this(orderRepository, productClient, outboxEventRepository, objectMapper, orderStateService, paymentAuthorizationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            OrderStateService orderStateService,
            PaymentAuthorizationService paymentAuthorizationService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.blackroth.training.mobilebackend.metrics.EventMetrics eventMetrics
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.orderStateService = orderStateService;
        this.paymentAuthorizationService = paymentAuthorizationService;
        this.eventMetrics = eventMetrics;
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

            return toResponse(confirmedOrder);

        } catch (Exception failure) {
            log.error("Order Saga failed for order {}", savedOrder.getId(), failure);
            boolean compensationSucceeded = true;

            // Release every order item because a timed-out reservation
            // request may have committed in Product Service.
            for (OrderItem item : savedOrder.getOrderItems()) {
                try {
                    ProductClient.ProductApiResponse release =
                            productClient.releaseReservation(
                                    item.getProductId(), savedOrder.getId());

                    if (release == null || !release.success()) {
                        compensationSucceeded = false;
                    }
                } catch (ResourceNotFoundException notFound) {
                    log.info(
                            "No reservation found for order {} item {}, release not required",
                            savedOrder.getId(),
                            item.getProductId());
                } catch (Exception compensationFailure) {
                    log.error(
                            "Compensation release failure for order {} item {}",
                            savedOrder.getId(),
                            item.getProductId(),
                            compensationFailure);
                    compensationSucceeded = false;
                }
            }

            if (compensationSucceeded) {
                orderStateService.markCancelled(savedOrder.getId());
                if (eventMetrics != null) {
                    eventMetrics.recordSagaCompensation("success");
                }
                log.info("Order Saga compensation completed: orderId={}, service=order-service, outcome=SAGA_COMPENSATION_COMPLETED",
                        savedOrder.getId());
            } else {
                orderStateService.markCompensationPending(savedOrder.getId());
                if (eventMetrics != null) {
                    eventMetrics.recordSagaCompensation("failed");
                }
                log.warn("Order Saga compensation pending recovery: orderId={}, service=order-service, outcome=SAGA_COMPENSATION_PENDING",
                        savedOrder.getId());
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

        Order cancelledOrder = orderStateService.markCancelled(id);

        return toResponse(cancelledOrder);
    }

    @Transactional
    public OrderResponse shipOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + id
                        )
                );

        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new BusinessException(
                    "Cannot ship order with status: " + order.getStatus()
                            + ". Only CONFIRMED orders can transition to SHIPPED"
            );
        }

        Order shippedOrder = orderStateService.markShipped(id);
        return toResponse(shippedOrder);
    }

    @Transactional
    public OrderResponse deliverOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + id
                        )
                );

        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException(
                    "Cannot deliver order with status: " + order.getStatus()
                            + ". Only SHIPPED orders can transition to DELIVERED"
            );
        }

        Order deliveredOrder = orderStateService.markDelivered(id);
        return toResponse(deliveredOrder);
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
