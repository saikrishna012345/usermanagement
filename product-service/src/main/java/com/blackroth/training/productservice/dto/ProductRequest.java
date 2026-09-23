package com.blackroth.training.productservice.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public class ProductRequest {
    @NotBlank
    private String name;
    private String description;
    @NotNull
    @Positive
    private BigDecimal price;
    @NotNull
    @PositiveOrZero
    private Integer stockQuantity;
    @NotNull
    private Long categoryId;

    public ProductRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal v) {
        price = v;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Integer v) {
        stockQuantity = v;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long v) {
        categoryId = v;
    }
}
