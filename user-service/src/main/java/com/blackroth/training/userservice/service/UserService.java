package com.blackroth.training.userservice.service;

import com.blackroth.training.userservice.dto.*;
import com.blackroth.training.userservice.exception.*;
import com.blackroth.training.userservice.model.User;
import com.blackroth.training.userservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public UserResponse create(UserRequest r) {
        if (r.getPassword() == null || r.getPassword().isBlank())
            throw new IllegalArgumentException("password is required when creating a user");
        if (repo.existsByEmail(r.getEmail()))
            throw new DuplicateUserException("Email already registered: " + r.getEmail());
        if (repo.existsByMobileNumber(r.getMobileNumber()))
            throw new DuplicateUserException("Mobile number already registered: " + r.getMobileNumber());
        User u = new User(r.getFirstName(), r.getLastName(), r.getEmail(), r.getMobileNumber(), encoder.encode(r.getPassword()), r.getRole(), r.getStatus());
        return toResponse(repo.save(u));
    }

    public UserResponse get(Long id) {
        return toResponse(repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id)));
    }

    public UserResponse update(Long id, UserRequest r) {
        User u = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        u.setFirstName(r.getFirstName());
        u.setLastName(r.getLastName());
        u.setEmail(r.getEmail());
        u.setMobileNumber(r.getMobileNumber());
        u.setStatus(r.getStatus());
        if (r.getRole() != null && !r.getRole().isBlank()) u.setRole(r.getRole());
        if (r.getPassword() != null && !r.getPassword().isBlank()) u.setPasswordHash(encoder.encode(r.getPassword()));
        return toResponse(repo.save(u));
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getMobileNumber(), u.getRole(), u.getStatus(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
