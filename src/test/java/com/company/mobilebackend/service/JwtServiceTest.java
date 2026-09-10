package com.company.mobilebackend.security;

import com.company.mobilebackend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-key-for-unit-tests-min-256-bits-long-enough");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirationMs", 900000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpirationMs", 604800000L);
    }

    @Test
    void generateAccessToken_containsCorrectEmailAndRole() {
        String token = jwtService.generateAccessToken("sai@example.com", "USER");

        assertThat(jwtService.extractEmail(token)).isEqualTo("sai@example.com");
        assertThat(jwtService.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void isTokenValid_returnsTrue_forMatchingEmailAndUnexpiredToken() {
        String token = jwtService.generateAccessToken("sai@example.com", "USER");

        assertThat(jwtService.isTokenValid(token, "sai@example.com")).isTrue();
    }

    @Test
    void isTokenValid_returnsFalse_forMismatchedEmail() {
        String token = jwtService.generateAccessToken("sai@example.com", "USER");

        assertThat(jwtService.isTokenValid(token, "someoneelse@example.com")).isFalse();
    }

    @Test
    void isTokenValid_returnsFalse_forMalformedToken() {
        assertThat(jwtService.isTokenValid("not.a.valid.token", "sai@example.com")).isFalse();
    }
}