package com.blackroth.training.mobilebackend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${app.jwt.secret}")
    private String secret;

    public JwtAuthenticationFilter() {
    }

    public JwtAuthenticationFilter(String secret) {
        this.secret = secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            String token = h.substring(7).trim();
            if (!token.isEmpty()) {
                try {
                    Claims c = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
                    if (c.getExpiration() != null && !c.getExpiration().before(new Date())) {
                        String role = c.get("role", String.class);
                        String subject = c.getSubject();

                        if (subject != null && !subject.isBlank() && role != null && !role.isBlank()) {
                            String normalizedRole = role.startsWith("ROLE_") ? role.substring(5).toUpperCase() : role.toUpperCase();
                            if ("ADMIN".equals(normalizedRole) || "USER".equals(normalizedRole)) {
                                var auth = new UsernamePasswordAuthenticationToken(
                                        subject,
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + normalizedRole))
                                );
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Invalid, expired, or malformed token fails closed
                }
            }
        }
        chain.doFilter(req, res);
    }
}
