package com.blackroth.training.mobilebackend.controller;

import com.blackroth.training.mobilebackend.dto.ApiResponse;
import com.blackroth.training.mobilebackend.dto.LoginRequest;
import com.blackroth.training.mobilebackend.dto.LoginResponse;
import com.blackroth.training.mobilebackend.dto.RefreshTokenRequest;
import com.blackroth.training.mobilebackend.dto.RegisterRequest;
import com.blackroth.training.mobilebackend.dto.RegisterResponse;
import com.blackroth.training.mobilebackend.dto.TokenRefreshResponse;
import com.blackroth.training.mobilebackend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")   
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse registered = authService.register(request);
        ApiResponse<RegisterResponse> response = ApiResponse.success("User registered successfully", registered);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse loginResponse = authService.login(request);
        ApiResponse<LoginResponse> response = ApiResponse.success("Login successful", loginResponse);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenRefreshResponse tokens = authService.refreshAccessToken(request.getRefreshToken());
        ApiResponse<TokenRefreshResponse> response = ApiResponse.success("Token refreshed successfully", tokens);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Object>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        ApiResponse<Object> response = ApiResponse.success("Logged out successfully", null);
        return ResponseEntity.ok(response);
    }
}