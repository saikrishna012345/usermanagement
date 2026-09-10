package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.LoginRequest;
import com.company.mobilebackend.dto.LoginResponse;
import com.company.mobilebackend.dto.RegisterRequest;
import com.company.mobilebackend.dto.RegisterResponse;
import com.company.mobilebackend.exception.DuplicateUserException;
import com.company.mobilebackend.exception.InvalidCredentialsException;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateUserException("Email already registered: " + request.getEmail());
        }
        if (userRepository.existsByMobileNumber(request.getMobileNumber())) {
            throw new DuplicateUserException("Mobile number already registered: " + request.getMobileNumber());
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User(
                request.getFirstName(),
                request.getLastName(),
                request.getEmail(),
                request.getMobileNumber(),
                hashedPassword,
                "USER",
                "ACTIVE"
        );

        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getFirstName(), saved.getLastName(),
                saved.getEmail(), saved.getRole());
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String accessToken = jwtService.generateAccessToken(user.getEmail(), user.getRole());
        String refreshToken = jwtService.generateRefreshToken(user.getEmail(), user.getRole());

        return new LoginResponse(user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), user.getRole(), accessToken, refreshToken);
    }
}