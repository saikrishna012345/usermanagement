package com.blackroth.training.mobilebackend.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FeignConfig Security and Token Propagation Tests")
class FeignConfigSecurityTest {

    private static final String TEST_SECRET = "test-secret-key-for-feign-config-security-min-256-bits-ok";

    @Test
    @DisplayName("Background thread without request context generates internal service JWT with ROLE_ADMIN")
    void whenBackgroundThread_thenGeneratesServiceJwtWithAdminRole() {
        RequestContextHolder.resetRequestAttributes();

        FeignConfig config = new FeignConfig(TEST_SECRET);
        RequestInterceptor interceptor = config.requestForwardingInterceptor();

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> authHeaders = template.headers().get("Authorization");
        assertNotNull(authHeaders, "Authorization header must be generated for background tasks");
        assertFalse(authHeaders.isEmpty());

        String authHeader = authHeaders.iterator().next();
        assertTrue(authHeader.startsWith("Bearer "));

        String token = authHeader.substring(7);
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        assertEquals("order-service", claims.getSubject());
        assertEquals("ROLE_ADMIN", claims.get("role"));
        assertEquals(0L, ((Number) claims.get("userId")).longValue());

        // Verify correlation ID is generated
        Collection<String> corrHeaders = template.headers().get("X-Correlation-ID");
        assertNotNull(corrHeaders);
        assertFalse(corrHeaders.isEmpty());
    }

    @Test
    @DisplayName("Active HTTP request forwards incoming user Authorization header and Correlation ID")
    void whenActiveHttpRequest_thenForwardsIncomingAuthorizationHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-incoming-token-12345");
        request.addHeader("X-Correlation-ID", "corr-req-777");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            FeignConfig config = new FeignConfig(TEST_SECRET);
            RequestInterceptor interceptor = config.requestForwardingInterceptor();

            RequestTemplate template = new RequestTemplate();
            interceptor.apply(template);

            Collection<String> authHeaders = template.headers().get("Authorization");
            assertNotNull(authHeaders);
            assertEquals("Bearer user-incoming-token-12345", authHeaders.iterator().next());

            Collection<String> corrHeaders = template.headers().get("X-Correlation-ID");
            assertNotNull(corrHeaders);
            assertEquals("corr-req-777", corrHeaders.iterator().next());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
