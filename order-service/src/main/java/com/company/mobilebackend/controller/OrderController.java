package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.*;
import com.company.mobilebackend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService s) {
        service = s;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderResponse>> create(@Valid @RequestBody OrderRequest r) {
        return new ResponseEntity<>(ApiResponse.success("Order created successfully", service.createOrder(r)), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order fetched successfully", service.getOrderById(id)));
    }

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> my(@RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.success("Orders fetched successfully", service.getOrdersByUser(userId)));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", service.cancelOrder(id)));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> byUser(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success("Orders fetched successfully", service.getOrdersByUser(userId)));
    }
}
