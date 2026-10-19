package com.blackroth.training.mobilebackend.event.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventPayload(
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        String orderStatus,
        List<OrderItemPayload> items
) {
    public void validate() {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (totalAmount == null || totalAmount.signum() < 0) {
            throw new IllegalArgumentException("totalAmount cannot be negative");
        }
        if (orderStatus == null || orderStatus.isBlank()) {
            throw new IllegalArgumentException("orderStatus is required and cannot be blank");
        }
        if (items != null) {
            items.forEach(OrderItemPayload::validate);
        }
    }
}
