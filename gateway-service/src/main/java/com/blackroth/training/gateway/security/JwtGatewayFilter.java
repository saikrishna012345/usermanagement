package com.blackroth.training.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Component
public class JwtGatewayFilter implements WebFilter {

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    @Value("${app.jwt.secret}")
    private String secret;

    public JwtGatewayFilter() {
    }

    public JwtGatewayFilter(String secret) {
        this.secret = secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Strip client-supplied identity headers immediately to prevent header spoofing
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(h -> {
                            h.remove("X-User-Email");
                            h.remove("X-User-Role");
                            h.remove("X-User-Id");
                        })
                        .build())
                .build();

        String path = sanitizedExchange.getRequest().getURI().getPath();
        org.springframework.http.HttpMethod method = sanitizedExchange.getRequest().getMethod();
        String authHeader = sanitizedExchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        boolean publicEndpoint = isPublic(method, path);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (publicEndpoint) {
                return chain.filter(sanitizedExchange);
            }
            return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            if (publicEndpoint) {
                return chain.filter(sanitizedExchange);
            }
            return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Missing token");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getExpiration() == null || claims.getExpiration().before(new Date())) {
                return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Token expired");
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Token missing required subject claim");
            }

            String rawRole = claims.get("role", String.class);
            if (rawRole == null || rawRole.isBlank()) {
                return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Token missing required role claim");
            }

            String normalizedRole = rawRole.startsWith("ROLE_")
                    ? rawRole.substring(5).toUpperCase()
                    : rawRole.toUpperCase();

            if (!ALLOWED_ROLES.contains(normalizedRole)) {
                return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Invalid role claim: " + rawRole);
            }

            Object userIdObj = claims.get("userId");
            String userIdStr = "";
            if (userIdObj != null) {
                if (userIdObj instanceof Number) {
                    userIdStr = String.valueOf(((Number) userIdObj).longValue());
                } else {
                    try {
                        long parsed = Long.parseLong(userIdObj.toString());
                        userIdStr = String.valueOf(parsed);
                    } catch (NumberFormatException e) {
                        return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Invalid numeric userId claim");
                    }
                }
            }

            ServerWebExchange authenticatedExchange = sanitizedExchange.mutate()
                    .request(sanitizedExchange.getRequest().mutate()
                            .header("X-User-Email", subject)
                            .header("X-User-Role", normalizedRole)
                            .header("X-User-Id", userIdStr)
                            .build())
                    .build();

            return chain.filter(authenticatedExchange);

        } catch (Exception e) {
            if (publicEndpoint) {
                return chain.filter(sanitizedExchange);
            }
            return reject(sanitizedExchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private Mono<Void> reject(ServerWebExchange e, HttpStatus s, String m) {
        e.getResponse().setStatusCode(s);
        e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"success\":false,\"status\":%d,\"message\":\"%s\",\"errorCode\":\"%s\"}",
                s.value(), m, s == HttpStatus.FORBIDDEN ? "FORBIDDEN" : "UNAUTHORIZED");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(bytes)));
    }
}
