package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.LoginRequest;
import com.company.mobilebackend.dto.LoginResponse;
import com.company.mobilebackend.exception.InvalidCredentialsException;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;

    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!user.getPassword().equals(request.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return new LoginResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail());
    }
}