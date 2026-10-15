package com.blackroth.training.mobilebackend.client;

import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    private static final Logger log =
            LoggerFactory.getLogger(ProductClientFallbackFactory.class);

    @Override
    public ProductClient create(Throwable cause) {
        log.error("ProductClient fallback invoked due to exception: {}",
                cause != null ? cause.toString() : "Unknown cause", cause);

        return new ProductClient() {
            @Override
            public ProductApiResponse getProduct(Long id) {
                handleException("getProduct", id, cause);
                return null;
            }

            @Override
            public ProductApiResponse decreaseStock(Long id, Integer quantity) {
                handleException("decreaseStock", id, cause);
                return null;
            }

            @Override
            public ProductApiResponse releaseStock(Long id, Integer quantity) {
                handleException("releaseStock", id, cause);
                return null;
            }

            @Override
            public ProductApiResponse reserveStock(Long id, Long orderId, Integer quantity) {
                handleException("reserveStock", id, cause);
                return null;
            }

            @Override
            public ProductApiResponse releaseReservation(Long id, Long orderId) {
                handleException("releaseReservation", id, cause);
                return null;
            }

            @Override
            public ProductApiResponse commitReservation(Long id, Long orderId) {
                handleException("commitReservation", id, cause);
                return null;
            }
        };
    }

    private void handleException(String method, Long id, Throwable cause) {
        Throwable root = cause;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            if (root instanceof FeignException) {
                break;
            }
            root = root.getCause();
        }

        if (root instanceof FeignException.NotFound || (root instanceof FeignException fe && fe.status() == 404)) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }
        if (root instanceof FeignException fe && (fe.status() == 400 || fe.status() == 409)) {
            throw new BusinessException(fe.getMessage() != null ? fe.getMessage() : "Product service business rule violation");
        }
        if (root instanceof FeignException fe && (fe.status() == 401 || fe.status() == 403)) {
            throw new BusinessException("Product service authorization failed (status: " + fe.status() + ")");
        }

        throw new ProductServiceUnavailableException("Product service is temporarily unavailable", cause);
    }
}
