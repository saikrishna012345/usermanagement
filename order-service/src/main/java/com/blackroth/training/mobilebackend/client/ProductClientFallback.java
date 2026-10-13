
package com.blackroth.training.mobilebackend.client;

import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class ProductClientFallback implements ProductClient {

    @Override
    public ProductClient.ProductApiResponse getProduct(Long id) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductClient.ProductApiResponse decreaseStock(
            Long id,
            Integer quantity
    ) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductClient.ProductApiResponse releaseStock(
            Long id,
            Integer quantity
    ) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductClient.ProductApiResponse reserveStock(
            Long id,
            Long orderId,
            Integer quantity
    ) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductClient.ProductApiResponse releaseReservation(
            Long id,
            Long orderId
    ) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

    @Override
    public ProductClient.ProductApiResponse commitReservation(
            Long id,
            Long orderId
    ) {
        throw new ProductServiceUnavailableException(
                "Product service is temporarily unavailable"
        );
    }

}