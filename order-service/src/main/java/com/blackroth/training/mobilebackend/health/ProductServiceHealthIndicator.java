package com.blackroth.training.mobilebackend.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for downstream Product Service availability.
 * Assesses operational readiness based on Resilience4j circuit breaker state.
 */
@Component
public class ProductServiceHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ProductServiceHealthIndicator(@Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Health health() {
        if (circuitBreakerRegistry == null) {
            return Health.up().withDetail("product-service", "CircuitBreakerRegistry not initialized").build();
        }

        CircuitBreaker cb = circuitBreakerRegistry.find("product-service")
                .or(() -> circuitBreakerRegistry.find("productService"))
                .orElse(null);

        if (cb == null) {
            return Health.up().withDetail("product-service", "Circuit breaker not registered").build();
        }

        CircuitBreaker.State state = cb.getState();
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
            return Health.down()
                    .withDetail("product-service", "DOWN")
                    .withDetail("circuitBreakerState", state.name())
                    .withDetail("failureRate", cb.getMetrics().getFailureRate() + "%")
                    .build();
        }

        return Health.up()
                .withDetail("product-service", "AVAILABLE")
                .withDetail("circuitBreakerState", state.name())
                .build();
    }
}
