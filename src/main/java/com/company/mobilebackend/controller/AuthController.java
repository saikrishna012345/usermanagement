package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.LoginRequest;
import com.company.mobilebackend.dto.LoginResponse;
import com.company.mobilebackend.dto.RegisterRequest;
import com.company.mobilebackend.dto.RegisterResponse;
import com.company.mobilebackend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
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
}