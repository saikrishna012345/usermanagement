package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.LoginRequest;
import com.company.mobilebackend.dto.LoginResponse;
import com.company.mobilebackend.dto.RegisterRequest;
import com.company.mobilebackend.dto.RegisterResponse;
import com.company.mobilebackend.exception.DuplicateUserException;
import com.company.mobilebackend.exception.InvalidCredentialsException;
import com.company.mobilebackend.exception.InvalidTokenException;
import com.company.mobilebackend.model.RefreshToken;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.RefreshTokenRepository;
import com.company.mobilebackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest validRequest;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        validRequest = new RegisterRequest();
        validRequest.setFirstName("Sai");
        validRequest.setLastName("Krishna");
        validRequest.setEmail("sai@example.com");
        validRequest.setMobileNumber("9876543210");
        validRequest.setPassword("secret123");
    }

    @Test
    void register_succeeds_andHashesPassword_whenNoDuplicates() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(false);
        when(userRepository.existsByMobileNumber(validRequest.getMobileNumber())).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed_secret123");
        when(userRepository.save(any())).thenAnswer(invocation -> {
            var user = invocation.getArgument(0, com.company.mobilebackend.model.User.class);
            user.setId(1L);
            return user;
        });

        RegisterResponse response = authService.register(validRequest);

        assertThat(response.getEmail()).isEqualTo("sai@example.com");
        assertThat(response.getRole()).isEqualTo("USER");
    }

    @Test
    void register_throwsDuplicateUserException_whenEmailExists() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(true);

        assertThrows(DuplicateUserException.class, () -> authService.register(validRequest));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void register_throwsDuplicateUserException_whenMobileNumberExists() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(false);
        when(userRepository.existsByMobileNumber(validRequest.getMobileNumber())).thenReturn(true);

        assertThrows(DuplicateUserException.class, () -> authService.register(validRequest));
    }

    @Test
    void login_succeeds_andReturnsTokens_withCorrectCredentials() {
        User user = new User("Sai", "Krishna", "sai@example.com", "9876543210",
                "hashed_secret123", "USER", "ACTIVE");
        LoginRequest request = new LoginRequest();
        request.setEmail("sai@example.com");
        request.setPassword("secret123");

        when(userRepository.findByEmail("sai@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hashed_secret123")).thenReturn(true);
        when(jwtService.generateAccessToken("sai@example.com", "USER")).thenReturn("access.token.value");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("access.token.value");
        assertThat(response.getRefreshToken()).isNotBlank();
    }

    @Test
    void login_throwsInvalidCredentialsException_withWrongPassword() {
        User user = new User("Sai", "Krishna", "sai@example.com", "9876543210",
                "hashed_secret123", "USER", "ACTIVE");
        LoginRequest request = new LoginRequest();
        request.setEmail("sai@example.com");
        request.setPassword("wrongpassword");

        when(userRepository.findByEmail("sai@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "hashed_secret123")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }

    @Test
    void logout_revokesToken_whenTokenExists() {
        RefreshToken token = new RefreshToken("some-uuid", null, LocalDateTime.now().plusDays(7));
        when(refreshTokenRepository.findByToken("some-uuid")).thenReturn(Optional.of(token));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.logout("some-uuid");

        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void refreshAccessToken_throwsInvalidTokenException_whenRevoked() {
        RefreshToken token = new RefreshToken("revoked-uuid", null, LocalDateTime.now().plusDays(7));
        token.setRevoked(true);
        when(refreshTokenRepository.findByToken("revoked-uuid")).thenReturn(Optional.of(token));

        assertThrows(InvalidTokenException.class, () -> authService.refreshAccessToken("revoked-uuid"));
    }
}