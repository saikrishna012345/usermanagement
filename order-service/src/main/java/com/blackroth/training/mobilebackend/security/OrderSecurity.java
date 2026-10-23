package com.blackroth.training.mobilebackend.security;

import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("orderSecurity")
public class OrderSecurity {

    private final OrderRepository orderRepository;

    public OrderSecurity(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean isOrderOwnerOrAdmin(Authentication authentication, Long orderId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            return true;
        }

        Object principal = authentication.getPrincipal();
        Long currentUserId = null;
        if (principal instanceof Long l) {
            currentUserId = l;
        } else if (principal instanceof Number n) {
            currentUserId = n.longValue();
        } else if (principal != null) {
            try {
                currentUserId = Long.valueOf(principal.toString().trim());
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        if (currentUserId == null) {
            return false;
        }

        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            // Allow request through so controller/service throws ResourceNotFoundException (404)
            return true;
        }

        return orderOpt.get().getUserId().equals(currentUserId);
    }
}
