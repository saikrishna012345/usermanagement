package com.blackroth.training.mobilebackend.event.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryUpdatedPayload(
        Long productId,
        Long orderId,
        Integer quantityChanged,
        String operation,
        Integer availableStock
) {
    public void validate() {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("productId must be positive");
        }
        if (quantityChanged == null) {
            throw new IllegalArgumentException("quantityChanged cannot be null");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required and cannot be blank");
        }
        if (availableStock == null || availableStock < 0) {
            throw new IllegalArgumentException("availableStock cannot be negative");
        }
    }
}
