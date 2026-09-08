package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.LoginRequest;
import com.company.mobilebackend.dto.LoginResponse;
import com.company.mobilebackend.exception.InvalidCredentialsException;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User("Sai", "Krishna", "sai@example.com", "9876543210", "secret123", "ACTIVE");
        existingUser.setId(1L);
    }

    @Test
    void login_succeeds_withCorrectCredentials() {
        LoginRequest request = new LoginRequest();
        request.setEmail("sai@example.com");
        request.setPassword("secret123");

        when(userRepository.findByEmail("sai@example.com")).thenReturn(Optional.of(existingUser));

        LoginResponse response = authService.login(request);

        assertThat(response.getEmail()).isEqualTo("sai@example.com");
        assertThat(response.getUserId()).isEqualTo(1L);
    }

    @Test
    void login_throwsInvalidCredentialsException_withWrongPassword() {
        LoginRequest request = new LoginRequest();
        request.setEmail("sai@example.com");
        request.setPassword("wrongpassword");

        when(userRepository.findByEmail("sai@example.com")).thenReturn(Optional.of(existingUser));

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }

    @Test
    void login_throwsInvalidCredentialsException_withUnknownEmail() {
        LoginRequest request = new LoginRequest();
        request.setEmail("unknown@example.com");
        request.setPassword("anything");

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }
}