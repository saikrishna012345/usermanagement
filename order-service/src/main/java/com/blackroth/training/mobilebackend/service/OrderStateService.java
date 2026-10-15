package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderStateService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderStateService(
            OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Order savePendingOrder(Order order) {
        order.setStatus(OrderStatus.PENDING);
        return orderRepository.saveAndFlush(order);
    }

    @Transactional
    public Order markConfirmed(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.CONFIRMED);
        Order savedOrder = orderRepository.saveAndFlush(order);
        saveOutboxEvent(savedOrder, "ORDER_CREATED", "order.created");
        return savedOrder;
    }

    @Transactional
    public Order markCompensationPending(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.COMPENSATION_PENDING);
        return orderRepository.saveAndFlush(order);
    }

    @Transactional
    public Order markCancelled(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        Order savedOrder = orderRepository.saveAndFlush(order);
        saveOutboxEvent(savedOrder, "ORDER_CANCELLED", "order.cancelled");
        return savedOrder;
    }

    private void saveOutboxEvent(
            Order order,
            String eventType,
            String topic
    ) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        OrderCreatedEvent event = new OrderCreatedEvent(
                eventId,
                eventType,
                "1.0",
                now,
                correlationId,
                order.getId(),
                order.getUserId(),
                order.getTotalAmount()
        );

        try {
            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setEventId(eventId);
            outboxEvent.setEventType(eventType);
            outboxEvent.setTopic(topic);
            outboxEvent.setAggregateId(order.getId().toString());
            outboxEvent.setPayload(objectMapper.writeValueAsString(event));
            outboxEvent.setStatus("PENDING");
            outboxEvent.setNextAttemptAt(now);

            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize " + eventType + " event",
                    exception
            );
        }
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + orderId
                        )
                );
    }
}