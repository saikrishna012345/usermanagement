package com.blackroth.training.mobilebackend.event;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreatedEvent(
        String eventId,
        String eventType,
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}