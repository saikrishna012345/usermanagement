package com.blackroth.training.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtGatewayFilter implements WebFilter {
    @Value("${app.jwt.secret}")
    private String secret;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isPublic(org.springframework.http.HttpMethod method, String path) {
        if (path.startsWith("/api/v1/auth/") || path.equals("/api/v1/health")
                || path.startsWith("/actuator") || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")) {
            return true;
        }
        if (org.springframework.http.HttpMethod.GET.equals(method)) {
            return path.startsWith("/api/v1/products") || path.startsWith("/api/v1/categories");
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        org.springframework.http.HttpMethod method = exchange.getRequest().getMethod();
        String h = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (isPublic(method, path) && (h == null || !h.startsWith("Bearer "))) {
            return chain.filter(exchange);
        }

        if (h == null || !h.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        try {
            Claims c = Jwts.parser().verifyWith(key()).build().parseSignedClaims(h.substring(7)).getPayload();
            if (c.getExpiration().before(new Date())) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Token expired");
            }
            Object userIdObj = c.get("userId");
            String userIdStr = userIdObj != null ? String.valueOf(userIdObj) : "";
            String role = c.get("role", String.class);

            ServerWebExchange mutated = exchange.mutate()
                    .request(exchange.getRequest().mutate()
                            .header("X-User-Email", c.getSubject() != null ? c.getSubject() : "")
                            .header("X-User-Role", role != null ? role : "")
                            .header("X-User-Id", userIdStr)
                            .build())
                    .build();
            return chain.filter(mutated);
        } catch (Exception e) {
            if (isPublic(method, path)) {
                return chain.filter(exchange);
            }
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private Mono<Void> reject(ServerWebExchange e, HttpStatus s, String m) {
        e.getResponse().setStatusCode(s);
        return e.getResponse().setComplete();
    }
}
