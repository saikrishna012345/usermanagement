package com.blackroth.training.mobilebackend.client;

import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class ProductClientFallback implements ProductClient {

    @Override
    public ProductResponse getProduct(Long id) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductResponse decreaseStock(Long id, Integer quantity) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }
}