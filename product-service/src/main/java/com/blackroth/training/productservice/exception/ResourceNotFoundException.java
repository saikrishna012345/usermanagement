package com.blackroth.training.productservice.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String m) {
        super(m);
    }
}
