package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.RegisterRequest;
import com.company.mobilebackend.dto.RegisterResponse;
import com.company.mobilebackend.exception.DuplicateUserException;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}