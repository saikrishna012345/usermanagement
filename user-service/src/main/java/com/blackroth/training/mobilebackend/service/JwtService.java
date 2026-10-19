package com.blackroth.training.mobilebackend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public JwtService() {
    }

    public JwtService(String secret, long accessTokenExpirationMs, long refreshTokenExpirationMs) {
        this.secret = secret;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String email, String role) {
        return generateAccessToken(null, email, role);
    }

    public String generateAccessToken(Long userId, String email, String role) {
        return buildToken(userId, email, role, accessTokenExpirationMs);
    }

    public String generateRefreshToken(String email, String role) {
        return buildToken(null, email, role, refreshTokenExpirationMs);
    }

    private String buildToken(Long userId, String email, String role, long expirationMs) {
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

        return builder.signWith(getSigningKey())
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Long extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object raw = claims.get("userId");
            if (raw instanceof Number) {
                return ((Number) raw).longValue();
            } else if (raw != null) {
                try {
                    return Long.valueOf(raw.toString().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        });
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> {
            String role = claims.get("role", String.class);
            if (role == null || role.isBlank()) {
                return null;
            }
            return role.startsWith("ROLE_") ? role.substring(5).toUpperCase() : role.toUpperCase();
        });
    }

    public boolean isTokenValid(String token, String expectedEmail) {
        try {
            String email = extractEmail(token);
            return email != null && email.equalsIgnoreCase(expectedEmail) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            return expiration == null || expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }
}