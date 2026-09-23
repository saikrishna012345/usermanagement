package com.blackroth.training.orderservice.controller;

import com.blackroth.training.orderservice.dto.*;
import com.blackroth.training.orderservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(@Valid @RequestBody OrderRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Order created successfully", service.create(r)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order fetched successfully", service.get(id)));
    }
}
