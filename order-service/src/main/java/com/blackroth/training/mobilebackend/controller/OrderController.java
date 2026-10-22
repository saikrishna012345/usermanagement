package com.blackroth.training.mobilebackend.controller;

import com.blackroth.training.mobilebackend.dto.ApiResponse;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import com.blackroth.training.mobilebackend.dto.*;
import com.blackroth.training.mobilebackend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;
    private final com.blackroth.training.mobilebackend.service.DltReplayService dltReplayService;

    public OrderController(OrderService s) {
        this(s, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderController(
            OrderService s,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.blackroth.training.mobilebackend.service.DltReplayService dltReplayService) {
        this.service = s;
        this.dltReplayService = dltReplayService;
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
    public ResponseEntity<ApiResponse<List<OrderResponse>>> my() {
        Long userId = (Long) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Orders fetched successfully",
                        service.getOrdersByUser(userId)
                )
        );
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

    @PatchMapping("/{id}/ship")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> ship(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order shipped successfully", service.shipOrder(id)));
    }

    @PatchMapping("/{id}/deliver")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> deliver(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Order delivered successfully", service.deliverOrder(id)));
    }

    @PostMapping("/admin/dlt/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> replayDlt(
            @RequestBody com.blackroth.training.mobilebackend.event.OrderCreatedEvent event) {
        if (dltReplayService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("DLT replay service is not available", "SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value()));
        }
        boolean replayed = dltReplayService.replayEvent(event);
        return ResponseEntity.ok(ApiResponse.success("DLT event replayed successfully", replayed));
    }
}
