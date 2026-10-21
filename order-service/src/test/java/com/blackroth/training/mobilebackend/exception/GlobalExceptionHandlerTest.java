package com.blackroth.training.mobilebackend.exception;

import com.blackroth.training.mobilebackend.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void resourceNotFound_returns404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Order not found with id: 999");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.notFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().getErrorCode());
        assertEquals("Order not found with id: 999", response.getBody().getMessage());
    }

    @Test
    void noResourceFound_returns404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.PATCH, "api/v1/orders/1/deliver");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleNoResourceFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().getErrorCode());
    }

    @Test
    void businessException_returns409Conflict() {
        BusinessException ex = new BusinessException("Cannot ship order with status: PENDING. Only CONFIRMED orders can transition to SHIPPED");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.business(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("BUSINESS_RULE_VIOLATION", response.getBody().getErrorCode());
        assertEquals("Cannot ship order with status: PENDING. Only CONFIRMED orders can transition to SHIPPED", response.getBody().getMessage());
    }

    @Test
    void accessDeniedException_returns403Forbidden() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.denied(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().getStatus());
        assertEquals("FORBIDDEN", response.getBody().getErrorCode());
    }

    @Test
    void genericException_returns500InternalServerError() {
        Exception ex = new RuntimeException("Unexpected database failure");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.generic(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().getStatus());
        assertEquals("INTERNAL_SERVER_ERROR", response.getBody().getErrorCode());
        assertEquals("Something went wrong. Please try again later.", response.getBody().getMessage());
    }
}
