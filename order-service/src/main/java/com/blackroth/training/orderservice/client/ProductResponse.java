package com.blackroth.training.orderservice.client;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price, Integer stockQuantity,
                              String categoryName) {

}
