package com.company.mobilebackend.dto;

import java.math.BigDecimal;

public class OrderStatisticsResponse {
    private Long totalOrders;
    private BigDecimal totalRevenue;
    private Double averageOrderValue;

    public OrderStatisticsResponse(Long totalOrders, BigDecimal totalRevenue, Double averageOrderValue) {
        this.totalOrders = totalOrders;
        this.totalRevenue = totalRevenue;
        this.averageOrderValue = averageOrderValue;
    }

    public Long getTotalOrders() { return totalOrders; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public Double getAverageOrderValue() { return averageOrderValue; }
}