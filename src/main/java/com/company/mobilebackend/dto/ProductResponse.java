package com.company.mobilebackend.dto;

import java.math.BigDecimal;

public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stockQuantity;
    private String categoryName;

    public ProductResponse() {
    }

    public ProductResponse(Long id, String name, String description, BigDecimal price,
                           Integer stockQuantity, String categoryName) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.categoryName = categoryName;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public Integer getStockQuantity() { return stockQuantity; }
    public String getCategoryName() { return categoryName; }
}