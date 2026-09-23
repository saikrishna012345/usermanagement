package com.company.mobilebackend.dto;

import java.math.BigDecimal;

public class TopCustomerResponse {
    private Long userId;
    private String firstName;
    private String lastName;
    private Long totalOrders;
    private BigDecimal totalSpent;

    public TopCustomerResponse(Long userId, String firstName, String lastName,
                               Long totalOrders, BigDecimal totalSpent) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.totalOrders = totalOrders;
        this.totalSpent = totalSpent;
    }

    public Long getUserId() { return userId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public Long getTotalOrders() { return totalOrders; }
    public BigDecimal getTotalSpent() { return totalSpent; }
}