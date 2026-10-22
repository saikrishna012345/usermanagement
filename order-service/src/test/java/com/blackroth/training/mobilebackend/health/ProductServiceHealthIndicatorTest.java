package com.blackroth.training.mobilebackend.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;

class ProductServiceHealthIndicatorTest {

    @Test
    @DisplayName("ProductServiceHealthIndicator returns UP when circuit breaker is CLOSED")
    void testHealthWhenCircuitBreakerClosed() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        registry.circuitBreaker("product-service");

        ProductServiceHealthIndicator indicator = new ProductServiceHealthIndicator(registry);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("AVAILABLE", health.getDetails().get("product-service"));
        assertEquals("CLOSED", health.getDetails().get("circuitBreakerState"));
    }

    @Test
    @DisplayName("ProductServiceHealthIndicator returns DOWN when circuit breaker is OPEN")
    void testHealthWhenCircuitBreakerOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        CircuitBreaker cb = registry.circuitBreaker("product-service");
        cb.transitionToOpenState();

        ProductServiceHealthIndicator indicator = new ProductServiceHealthIndicator(registry);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("product-service"));
        assertEquals("OPEN", health.getDetails().get("circuitBreakerState"));
    }

    @Test
    @DisplayName("ProductServiceHealthIndicator handles missing registry or circuit breaker safely")
    void testHealthWhenRegistryNull() {
        ProductServiceHealthIndicator indicator = new ProductServiceHealthIndicator(null);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().containsKey("product-service"));
    }
}
