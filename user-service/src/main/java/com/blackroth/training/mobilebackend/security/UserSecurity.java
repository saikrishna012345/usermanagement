package com.blackroth.training.mobilebackend.security;

import com.blackroth.training.mobilebackend.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("userSecurity")
public class UserSecurity {

    private final UserRepository userRepository;

    public UserSecurity(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isCurrentUserOrAdmin(Authentication authentication, Long userId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            return true;
        }

        String currentEmail = authentication.getName();
        return userRepository.findById(userId)
                .map(user -> user.getEmail().equalsIgnoreCase(currentEmail))
                .orElse(false);
    }
}
