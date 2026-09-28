package com.company.mobilebackend.client;

import org.springframework.cloud.openfeign.FeignClient;
import com.company.mobilebackend.config.FeignConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "product-service", configuration = FeignConfig.class)
public interface ProductClient {
    @GetMapping("/api/v1/products/{id}")
    ProductResponse getProduct(@PathVariable("id") Long id);

    @PatchMapping("/api/v1/products/{id}/stock")
    ProductResponse decreaseStock(@PathVariable("id") Long id, @RequestParam("quantity") Integer quantity);

    record ProductResponse(Long id, String name, String description, BigDecimal price, Integer stockQuantity,
                           String categoryName) {
    }
}
