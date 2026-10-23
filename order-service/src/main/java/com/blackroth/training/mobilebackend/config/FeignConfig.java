
package com.blackroth.training.mobilebackend.config;

import feign.RequestInterceptor;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Configuration
public class FeignConfig {

    private static final String CORRELATION_ID = "X-Correlation-ID";

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    public FeignConfig() {
    }

    public FeignConfig(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Bean
    public RequestInterceptor requestForwardingInterceptor() {
        return template -> {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes)
                            RequestContextHolder.getRequestAttributes();

            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();

                String authorization = request.getHeader("Authorization");
                if (authorization != null && !authorization.isBlank()) {
                    template.header("Authorization", authorization);
                }

                String correlationId = MDC.get("correlationId");
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = request.getHeader(CORRELATION_ID);
                }

                if (correlationId != null && !correlationId.isBlank()) {
                    template.header(CORRELATION_ID, correlationId);
                }
            } else {
                // Background thread execution (e.g. Saga compensation recovery poller)
                if (jwtSecret != null && !jwtSecret.isBlank()) {
                    String serviceToken = generateServiceToken(jwtSecret);
                    template.header("Authorization", "Bearer " + serviceToken);
                }

                String correlationId = MDC.get("correlationId");
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = UUID.randomUUID().toString();
                }
                template.header(CORRELATION_ID, correlationId);
            }
        };
    }

    public String generateServiceToken(String secret) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 120_000); // 2 minutes
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("order-service")
                .claim("userId", 0L)
                .claim("role", "ROLE_ADMIN")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }
}
