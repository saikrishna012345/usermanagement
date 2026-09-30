package com.blackroth.training.mobilebackend.exception;

public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(String message) {
        super(message);
    }

    public ProductServiceUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}