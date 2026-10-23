package com.blackroth.training.gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwtGatewaySecurityTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-signing-must-be-min-256-bits-long-ok";
    private JwtGatewayFilter filter;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        filter = new JwtGatewayFilter(TEST_SECRET);
        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String generateToken(Long userId, String email, String role, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        var builder = Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry);

        if (userId != null) {
            builder.claim("userId", userId);
        }

        return builder.signWith(signingKey).compact();
    }

    @Test
    void missingToken_onProtectedEndpoint_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertFalse(chainCalled.get(), "Chain should not be executed for missing token");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void malformedToken_onProtectedEndpoint_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void invalidSignature_returns401() {
        SecretKey untrustedKey = Keys.hmacShaKeyFor("untrusted-different-secret-key-for-test-tampering-bits".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = Jwts.builder()
                .subject("user@test.com")
                .claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(untrustedKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void expiredToken_returns401() {
        String expiredToken = generateToken(1L, "user@test.com", "USER", -5000);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void missingSubjectClaim_returns401() {
        String tokenNoSub = Jwts.builder()
                .claim("role", "USER")
                .claim("userId", 1L)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNoSub)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void missingRoleClaim_failsClosed_returns401() {
        String tokenNoRole = Jwts.builder()
                .subject("user@test.com")
                .claim("userId", 1L)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNoRole)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void invalidRoleClaim_failsClosed_returns401() {
        String tokenInvalidRole = Jwts.builder()
                .subject("user@test.com")
                .claim("role", "SUPER_ADMIN_HACK")
                .claim("userId", 1L)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenInvalidRole)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void invalidNumericClaim_failsClosed_returns401() {
        String tokenBadNumber = Jwts.builder()
                .subject("user@test.com")
                .claim("role", "USER")
                .claim("userId", "not-a-number")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBadNumber)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void validToken_authenticatesAndPopulatesSanitizedIdentityHeaders() {
        String validToken = generateToken(42L, "testuser@domain.com", "USER", 60000);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .header("X-User-Role", "FAKE_ADMIN") // Attempt spoofing
                .header("X-User-Id", "9999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwardedHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwardedHeaders.get());
        assertEquals("testuser@domain.com", forwardedHeaders.get().getFirst("X-User-Email"));
        assertEquals("USER", forwardedHeaders.get().getFirst("X-User-Role"));
        assertEquals("42", forwardedHeaders.get().getFirst("X-User-Id"));
    }

    @Test
    void validToken_withRolePrefix_normalizesRole() {
        String validToken = generateToken(10L, "admin@domain.com", "ROLE_ADMIN", 60000);

        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwardedHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwardedHeaders.get());
        assertEquals("ADMIN", forwardedHeaders.get().getFirst("X-User-Role"));
    }

    @Test
    void publicEndpoint_stripsSpoofedIdentityHeaders_allowsRequest() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/products")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1")
                .header("X-User-Email", "admin@spoofed.com")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwardedHeaders.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwardedHeaders.get());
        assertNull(forwardedHeaders.get().getFirst("X-User-Role"), "Spoofed X-User-Role must be stripped");
        assertNull(forwardedHeaders.get().getFirst("X-User-Id"), "Spoofed X-User-Id must be stripped");
        assertNull(forwardedHeaders.get().getFirst("X-User-Email"), "Spoofed X-User-Email must be stripped");
    }

    @Test
    void publicAuthEndpoint_withoutToken_succeeds() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.POST, "/api/v1/auth/login")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(chainCalled.get(), "Public auth endpoint must proceed without token");
    }

    @Test
    void actuatorHealth_withoutToken_succeeds() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/actuator/health")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(chainCalled.get(), "/actuator/health must be public for discovery and probes");
    }

    @Test
    void actuatorPrometheus_withoutToken_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/actuator/prometheus")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void actuatorPrometheus_withUserRole_returns403() {
        String userToken = generateToken(2L, "user@test.com", "USER", 60000);
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void actuatorPrometheus_withAdminRole_succeeds() {
        String adminToken = generateToken(1L, "admin@test.com", "ADMIN", 60000);
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertTrue(chainCalled.get(), "/actuator/prometheus must be allowed for ADMIN");
    }
}
