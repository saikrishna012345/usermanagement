package com.blackroth.training.productservice.exception;

import com.blackroth.training.productservice.dto.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> nf(ResourceNotFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage(), "PRODUCT_NOT_FOUND"));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiResponse<Void>> stock(InsufficientStockException e) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, e.getMessage(), "INSUFFICIENT_STOCK"));
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
