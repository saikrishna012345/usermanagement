package com.blackroth.training.orderservice.dto;

import com.blackroth.training.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(Long id, Long userId, OrderStatus status, BigDecimal totalAmount, LocalDateTime createdAt,
                            List<OrderItemResponse> items) {

}
