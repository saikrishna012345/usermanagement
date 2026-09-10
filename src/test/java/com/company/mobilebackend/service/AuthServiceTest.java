package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.RegisterRequest;
import com.company.mobilebackend.dto.RegisterResponse;
import com.company.mobilebackend.exception.DuplicateUserException;
import com.company.mobilebackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
}