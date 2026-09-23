package com.blackroth.training.orderservice.exception;

import com.blackroth.training.orderservice.dto.ApiResponse;
import feign.FeignException;
import feign.RetryableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import static com.blackroth.training.orderservice.exception.OrderExceptions.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(OrderNotFound.class)
    ResponseEntity<ApiResponse<Void>> orderNotFound(OrderNotFound e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage(), "ORDER_NOT_FOUND"));
    }

    @ExceptionHandler(ProductNotFound.class)
    ResponseEntity<ApiResponse<Void>> nf(ProductNotFound e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage(), "PRODUCT_NOT_FOUND"));
    }

    @ExceptionHandler(InsufficientStock.class)
    ResponseEntity<ApiResponse<Void>> stock(InsufficientStock e) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, e.getMessage(), "INSUFFICIENT_STOCK"));
    }

    @ExceptionHandler(ProductServiceTimeout.class)
    ResponseEntity<ApiResponse<Void>> timeout(ProductServiceTimeout e) {
        return ResponseEntity.status(504).body(ApiResponse.error(504, "Product Service request timed out", "PRODUCT_SERVICE_TIMEOUT"));
    }

    @ExceptionHandler(ProductServiceUnavailable.class)
    ResponseEntity<ApiResponse<Void>> unavailable(ProductServiceUnavailable e) {
        return ResponseEntity.status(503).body(ApiResponse.error(503, "Product Service is unavailable", "PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @ExceptionHandler(RetryableException.class)
    ResponseEntity<ApiResponse<Void>> retry(RetryableException e) {
        return ResponseEntity.status(503).body(ApiResponse.error(503, "Product Service connection failed", "PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> val(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid request", "VALIDATION_ERROR"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> generic(Exception e) {
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error", "INTERNAL_ERROR"));
    }
}
