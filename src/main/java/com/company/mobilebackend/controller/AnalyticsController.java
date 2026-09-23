package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.BestSellingProductResponse;
import com.company.mobilebackend.dto.OrderResponse;
import com.company.mobilebackend.dto.OrderStatisticsResponse;
import com.company.mobilebackend.dto.PagedResponse;
import com.company.mobilebackend.dto.TopCustomerResponse;
import com.company.mobilebackend.service.AnalyticsService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/orders/high-value")
    public ResponseEntity<ApiResponse<PagedResponse<OrderResponse>>> getHighValueOrders(
            @RequestParam(defaultValue = "1000") BigDecimal threshold,
            @PageableDefault(size = 10) Pageable pageable) {
        PagedResponse<OrderResponse> orders = analyticsService.getHighValueOrders(threshold, pageable);
        ApiResponse<PagedResponse<OrderResponse>> response = ApiResponse.success("High-value orders fetched successfully", orders);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users/top-customers")
    public ResponseEntity<ApiResponse<List<TopCustomerResponse>>> getTopCustomers(
            @RequestParam(defaultValue = "10") int limit) {
        List<TopCustomerResponse> customers = analyticsService.getTopCustomers(limit);
        ApiResponse<List<TopCustomerResponse>> response = ApiResponse.success("Top customers fetched successfully", customers);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/products/best-selling")
    public ResponseEntity<ApiResponse<List<BestSellingProductResponse>>> getBestSellingProducts(
            @RequestParam(defaultValue = "10") int limit) {
        List<BestSellingProductResponse> products = analyticsService.getBestSellingProducts(limit);
        ApiResponse<List<BestSellingProductResponse>> response = ApiResponse.success("Best-selling products fetched successfully", products);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/orders/statistics")
    public ResponseEntity<ApiResponse<OrderStatisticsResponse>> getOrderStatistics() {
        OrderStatisticsResponse stats = analyticsService.getOrderStatistics();
        ApiResponse<OrderStatisticsResponse> response = ApiResponse.success("Order statistics fetched successfully", stats);
        return ResponseEntity.ok(response);
    }
}