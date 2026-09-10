package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.OrderRequest;
import com.company.mobilebackend.dto.OrderResponse;
import com.company.mobilebackend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/api/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse created = orderService.createOrder(request);
        ApiResponse<OrderResponse> response = ApiResponse.success("Order created successfully", created);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/orders/my")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyOrders() {
        List<OrderResponse> orders = orderService.getMyOrders();
        ApiResponse<List<OrderResponse>> response = ApiResponse.success("Orders fetched successfully", orders);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/orders/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable Long id) {
        OrderResponse order = orderService.getOrderById(id);
        ApiResponse<OrderResponse> response = ApiResponse.success("Order fetched successfully", order);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/users/{userId}/orders")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrdersByUser(@PathVariable Long userId) {
        List<OrderResponse> orders = orderService.getOrdersByUser(userId);
        ApiResponse<List<OrderResponse>> response = ApiResponse.success("Orders fetched successfully", orders);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/api/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(@PathVariable Long id) {
        OrderResponse cancelled = orderService.cancelOrder(id);
        ApiResponse<OrderResponse> response = ApiResponse.success("Order cancelled successfully", cancelled);
        return ResponseEntity.ok(response);
    }
}