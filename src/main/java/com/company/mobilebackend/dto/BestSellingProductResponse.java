package com.company.mobilebackend.dto;

public class BestSellingProductResponse {
    private Long productId;
    private String productName;
    private Long totalQuantitySold;

    public BestSellingProductResponse(Long productId, String productName, Long totalQuantitySold) {
        this.productId = productId;
        this.productName = productName;
        this.totalQuantitySold = totalQuantitySold;
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Long getTotalQuantitySold() { return totalQuantitySold; }
}