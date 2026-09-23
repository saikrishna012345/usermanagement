package com.blackroth.training.userservice.dto;

import java.time.LocalDateTime;

public record UserResponse(Long id, String firstName, String lastName, String email, String mobileNumber, String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
