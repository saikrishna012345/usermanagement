package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.client.ProductClientFallbackFactory;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenFeign Timeout, Fallback, Circuit Breaker, and Retry Resilience Tests")
class OpenFeignResilienceTest {

    private ProductClientFallbackFactory fallbackFactory;
    private Request dummyRequest;

    @BeforeEach
    void setUp() {
        fallbackFactory = new ProductClientFallbackFactory();
        dummyRequest = Request.create(
                Request.HttpMethod.GET,
                "/api/v1/products/1",
                Collections.emptyMap(),
                null,
                new RequestTemplate()
        );
    }

    @Test
    @DisplayName("Fallback maps 404 NotFound to ResourceNotFoundException without pretending success")
    void fallbackMaps404ToResourceNotFoundException() {
        Response response = Response.builder()
                .status(404)
                .reason("Not Found")
                .request(dummyRequest)
                .build();
        FeignException.NotFound feignNotFound = new FeignException.NotFound(
                "Product not found",
                dummyRequest,
                null,
                Collections.emptyMap()
        );

        ProductClient client = fallbackFactory.create(feignNotFound);

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> client.getProduct(999L)
        );
        assertTrue(ex.getMessage().contains("999"));
    }

    @Test
    @DisplayName("Fallback maps 400 Bad Request to BusinessException")
    void fallbackMaps400ToBusinessException() {
        Response response = Response.builder()
                .status(400)
                .reason("Bad Request")
                .request(dummyRequest)
                .build();
        FeignException badRequestEx = FeignException.errorStatus("reserveStock", response);

        ProductClient client = fallbackFactory.create(badRequestEx);

        assertThrows(
                BusinessException.class,
                () -> client.reserveStock(1L, 100L, 5)
        );
    }

    @Test
    @DisplayName("Fallback maps 409 Conflict to BusinessException")
    void fallbackMaps409ToBusinessException() {
        Response response = Response.builder()
                .status(409)
                .reason("Conflict")
                .request(dummyRequest)
                .build();
        FeignException conflictEx = FeignException.errorStatus("reserveStock", response);

        ProductClient client = fallbackFactory.create(conflictEx);

        assertThrows(
                BusinessException.class,
                () -> client.reserveStock(1L, 100L, 5)
        );
    }

    @Test
    @DisplayName("Fallback maps read timeout to ProductServiceUnavailableException with cause preserved")
    void fallbackMapsReadTimeoutToProductServiceUnavailableException() {
        SocketTimeoutException socketTimeout = new SocketTimeoutException("Read timed out");
        RetryableException timeoutEx = new RetryableException(
                504,
                "Read timed out",
                Request.HttpMethod.POST,
                socketTimeout,
                0L,
                dummyRequest
        );

        ProductClient client = fallbackFactory.create(timeoutEx);

        ProductServiceUnavailableException thrown = assertThrows(
                ProductServiceUnavailableException.class,
                () -> client.reserveStock(1L, 100L, 2)
        );

        assertTrue(thrown.getMessage().contains("temporarily unavailable"));
        assertNotNull(thrown.getCause());
        assertSame(timeoutEx, thrown.getCause());
    }

    @Test
    @DisplayName("Fallback maps 503 Service Unavailable to ProductServiceUnavailableException")
    void fallbackMaps503ToProductServiceUnavailableException() {
        Response response = Response.builder()
                .status(503)
                .reason("Service Unavailable")
                .request(dummyRequest)
                .build();
        FeignException unavailableEx = FeignException.errorStatus("commitReservation", response);

        ProductClient client = fallbackFactory.create(unavailableEx);

        assertThrows(
                ProductServiceUnavailableException.class,
                () -> client.commitReservation(1L, 100L)
        );
    }

    @Test
    @DisplayName("Fallback never returns null or mock data indicating inventory was reserved")
    void fallbackNeverFalselyReportsInventoryReserved() {
        RuntimeException connectionRefused = new RuntimeException("Connection refused");
        ProductClient client = fallbackFactory.create(connectionRefused);

        // Every mutating reservation method MUST throw ProductServiceUnavailableException
        assertThrows(ProductServiceUnavailableException.class,
                () -> client.reserveStock(1L, 100L, 2));

        assertThrows(ProductServiceUnavailableException.class,
                () -> client.releaseReservation(1L, 100L));

        assertThrows(ProductServiceUnavailableException.class,
                () -> client.commitReservation(1L, 100L));

        assertThrows(ProductServiceUnavailableException.class,
                () -> client.decreaseStock(1L, 2));

        assertThrows(ProductServiceUnavailableException.class,
                () -> client.releaseStock(1L, 2));
    }

    @Test
    @DisplayName("Resilience4j Circuit Breaker transitions from CLOSED to OPEN on threshold breach")
    void circuitBreakerTransitionsClosedToOpenOnFailureRate() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("product-service-cb", config);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        Supplier<String> failingCall = () -> {
            throw new RuntimeException("Product Service is down");
        };

        Supplier<String> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, failingCall);

        // Execute 5 failing calls to meet minimumNumberOfCalls (5/5 = 100% >= 50%)
        for (int i = 0; i < 5; i++) {
            assertThrows(RuntimeException.class, decorated::get);
        }

        // Circuit breaker should now be OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Subsequent call is fast-rejected with CallNotPermittedException without invoking the downstream
        CallNotPermittedException rejectedEx = assertThrows(
                CallNotPermittedException.class,
                decorated::get
        );
        assertTrue(rejectedEx.getMessage().contains("product-service-cb"));

        // Verify fallback factory translates CallNotPermittedException to ProductServiceUnavailableException
        ProductClient fallbackClient = fallbackFactory.create(rejectedEx);
        assertThrows(ProductServiceUnavailableException.class, () -> fallbackClient.getProduct(1L));
    }

    @Test
    @DisplayName("Resilience4j Circuit Breaker transitions to HALF_OPEN and recovers to CLOSED")
    void circuitBreakerRecoversFromOpenToHalfOpenToClosed() throws InterruptedException {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("product-service-recovery-cb", config);

        // Force open
        circuitBreaker.transitionToOpenState();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for waitDurationInOpenState to elapse
        Thread.sleep(150);

        // State should transition to HALF_OPEN on first call or manually
        circuitBreaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Now execute 2 successful calls in HALF_OPEN state
        Supplier<String> healthyCall = () -> "OK";
        Supplier<String> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, healthyCall);

        assertEquals("OK", decorated.get());
        assertEquals("OK", decorated.get());

        // Circuit breaker should now recover to CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Resilience4j Retry is bounded to configured maxAttempts (3 attempts)")
    void retryIsBoundedToMaxAttempts() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ProductServiceUnavailableException.class)
                .build();

        Retry retry = Retry.of("product-service-retry", config);

        AtomicInteger callCount = new AtomicInteger(0);

        Supplier<String> failingCall = () -> {
            callCount.incrementAndGet();
            throw new ProductServiceUnavailableException("Product Service connection timeout");
        };

        Supplier<String> decorated = Retry.decorateSupplier(retry, failingCall);

        assertThrows(ProductServiceUnavailableException.class, decorated::get);

        // Bounded retry verification: exactly 3 attempts executed
        assertEquals(3, callCount.get());
    }

    @Test
    @DisplayName("Resilience4j Retry succeeds when transient failure resolves within bounds")
    void retrySucceedsWhenTransientFailureResolves() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ProductServiceUnavailableException.class)
                .build();

        Retry retry = Retry.of("product-service-retry-success", config);

        AtomicInteger callCount = new AtomicInteger(0);

        Supplier<String> transientCall = () -> {
            int attempt = callCount.incrementAndGet();
            if (attempt < 2) {
                throw new ProductServiceUnavailableException("Transient timeout");
            }
            return "SUCCESS";
        };

        Supplier<String> decorated = Retry.decorateSupplier(retry, transientCall);

        String result = decorated.get();
        assertEquals("SUCCESS", result);
        assertEquals(2, callCount.get());
    }
}
