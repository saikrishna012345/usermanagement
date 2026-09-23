package com.blackroth.training.orderservice.dto;

import java.math.BigDecimal;

public record OrderItemResponse(Long productId, String productName, Integer quantity, BigDecimal unitPrice,
                                BigDecimal subtotal) {

}
