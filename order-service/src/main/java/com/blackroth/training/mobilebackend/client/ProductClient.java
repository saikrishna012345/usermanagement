
package com.blackroth.training.mobilebackend.client;

import com.blackroth.training.mobilebackend.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(
        name = "product-service",
        configuration = FeignConfig.class,
        fallback = ProductClientFallback.class
)
public interface ProductClient {

    @GetMapping("/api/v1/products/{id}")
    ProductApiResponse getProduct(@PathVariable("id") Long id);

    @PatchMapping("/api/v1/products/{id}/stock")
    ProductApiResponse decreaseStock(
            @PathVariable("id") Long id,
            @RequestParam("quantity") Integer quantity
    );

    record ProductApiResponse(
            boolean success,
            Integer status,
            String message,
            ProductResponse data,
            String errorCode,
            String timestamp
    ) {
    }

    record ProductResponse(
            Long id,
            String name,
            String description,
            BigDecimal price,
            Integer stockQuantity,
            String categoryName
    ) {
    }
}
