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

    public void validate() {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required and cannot be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required and cannot be blank");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required and cannot be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId must be a positive number");
        }
    }
}