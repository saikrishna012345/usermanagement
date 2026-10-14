package com.blackroth.training.mobilebackend.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedEvent(
        String eventId,
        String eventType,
        String schemaVersion,
        Instant occurredAt,
        String correlationId,
        Long orderId,
        Long userId,
        BigDecimal totalAmount
) {
    public OrderCreatedEvent(
            String eventId,
            String eventType,
            Long orderId,
            Long userId,
            BigDecimal totalAmount,
            Instant occurredAt
    ) {
        this(eventId, eventType, "1.0", occurredAt, null, orderId, userId, totalAmount);
    }
}