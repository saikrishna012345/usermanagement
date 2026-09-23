package com.company.mobilebackend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class OrderItemRequest {
    @NotNull
    private Long productId;

    @NotNull
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    public OrderItemRequest() {
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}