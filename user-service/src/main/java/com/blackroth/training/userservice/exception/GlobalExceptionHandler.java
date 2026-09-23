package com.blackroth.training.userservice.exception;

import com.blackroth.training.userservice.dto.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, ex.getMessage(), "USER_NOT_FOUND"));
    }

    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<ApiResponse<Void>> duplicate(DuplicateUserException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(409, ex.getMessage(), "DUPLICATE_USER"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "Invalid request", "VALIDATION_ERROR"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> generic(Exception ex) {
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error", "INTERNAL_ERROR"));
    }
}
