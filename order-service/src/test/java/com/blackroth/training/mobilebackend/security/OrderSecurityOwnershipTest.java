package com.blackroth.training.mobilebackend.security;

import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Order Security & Ownership (BOLA/IDOR Protection) Tests")
class OrderSecurityOwnershipTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderSecurity orderSecurity;

    private static final Long ORDER_ID = 100L;
    private static final Long OWNER_USER_ID = 42L;
    private static final Long ATTACKER_USER_ID = 99L;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        orderSecurity = new OrderSecurity(orderRepository);
        testOrder = new Order(OWNER_USER_ID, OrderStatus.PENDING, BigDecimal.valueOf(150.00));
        testOrder.setId(ORDER_ID);
    }

    private Authentication createAuth(Long userId, String role) {
        return new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    @Test
    @DisplayName("Owner can access their own order")
    void whenUserIsOwner_thenAccessAllowed() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(testOrder));

        Authentication ownerAuth = createAuth(OWNER_USER_ID, "USER");
        boolean allowed = orderSecurity.isOrderOwnerOrAdmin(ownerAuth, ORDER_ID);

        assertTrue(allowed, "Owner must be allowed to access their own order");
    }

    @Test
    @DisplayName("Admin can access any order")
    void whenUserIsAdmin_thenAccessAllowed() {
        Authentication adminAuth = createAuth(ATTACKER_USER_ID, "ADMIN");
        boolean allowed = orderSecurity.isOrderOwnerOrAdmin(adminAuth, ORDER_ID);

        assertTrue(allowed, "ADMIN must be allowed to access any order");
        verify(orderRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("Non-owner (attacker) is denied access (IDOR protection)")
    void whenUserIsNotOwnerAndNotAdmin_thenAccessDenied() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(testOrder));

        Authentication attackerAuth = createAuth(ATTACKER_USER_ID, "USER");
        boolean allowed = orderSecurity.isOrderOwnerOrAdmin(attackerAuth, ORDER_ID);

        assertFalse(allowed, "Non-owner must be denied access to prevent BOLA/IDOR");
    }

    @Test
    @DisplayName("Non-existent order falls through to controller/service for 404")
    void whenOrderDoesNotExist_thenReturnsTrueToAllowService404() {
        when(orderRepository.findById(9999L)).thenReturn(Optional.empty());

        Authentication userAuth = createAuth(OWNER_USER_ID, "USER");
        boolean allowed = orderSecurity.isOrderOwnerOrAdmin(userAuth, 9999L);

        assertTrue(allowed, "Non-existent order should fall through to service to produce 404 Not Found");
    }

    @Test
    @DisplayName("Unauthenticated or null authentication is denied")
    void whenAuthenticationIsNull_thenAccessDenied() {
        assertFalse(orderSecurity.isOrderOwnerOrAdmin(null, ORDER_ID));
    }
}
