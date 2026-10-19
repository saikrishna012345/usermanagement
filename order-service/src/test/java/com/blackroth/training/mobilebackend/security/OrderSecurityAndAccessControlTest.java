package com.blackroth.training.mobilebackend.security;

import com.blackroth.training.mobilebackend.controller.OrderController;
import com.blackroth.training.mobilebackend.dto.ApiResponse;
import com.blackroth.training.mobilebackend.dto.OrderResponse;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.service.OrderService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderSecurityAndAccessControlTest {

    private static final String TEST_SECRET = "test-secret-key-for-order-service-security-min-256-bits-ok";
    private JwtAuthenticationFilter filter;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(TEST_SECRET);
        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String buildToken(Long userId, String role, long expirationMs) {
        var builder = Jwts.builder()
                .subject("order-user@test.com")
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs));

        if (userId != null) {
            builder.claim("userId", userId);
        }

        return builder.signWith(signingKey).compact();
    }

    @Test
    void missingToken_failsClosed_leavesContextEmpty() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void malformedToken_failsClosed() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer invalid-tampered-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void expiredToken_failsClosed() throws ServletException, IOException {
        String token = buildToken(1L, "USER", -5000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingUserId_failsClosed() throws ServletException, IOException {
        String token = buildToken(null, "USER", 60000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingRole_failsClosed() throws ServletException, IOException {
        String token = buildToken(1L, null, 60000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void invalidRole_failsClosed() throws ServletException, IOException {
        String token = buildToken(1L, "SUPER_ADMIN", 60000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validUserToken_authenticatesWithNormalizedRoleUser() throws ServletException, IOException {
        String token = buildToken(123L, "USER", 60000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(123L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void validAdminToken_normalizesRoleAdmin() throws ServletException, IOException {
        String token = buildToken(999L, "ROLE_ADMIN", 60000);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (r, s) -> {});

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(999L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void adminShippingOperation_succeedsWhenOrderStateIsConfirmed() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);

        OrderResponse shipped = new OrderResponse(
                10L, 1L, OrderStatus.SHIPPED, java.math.BigDecimal.valueOf(100),
                java.time.LocalDateTime.now(), java.util.List.of()
        );
        when(service.shipOrder(10L)).thenReturn(shipped);

        ResponseEntity<ApiResponse<OrderResponse>> response = controller.ship(10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals(OrderStatus.SHIPPED, response.getBody().getData().getStatus());
        verify(service).shipOrder(10L);
    }

    @Test
    void adminShippingOperation_failsWhenOrderStateIsInvalid_preservesBusinessValidation() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);

        when(service.shipOrder(10L)).thenThrow(new BusinessException(
                "Cannot ship order with status: PENDING. Only CONFIRMED orders can transition to SHIPPED"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.ship(10L));
        assertTrue(ex.getMessage().contains("Only CONFIRMED orders can transition to SHIPPED"));
    }

    @Test
    void adminDeliveryOperation_succeedsWhenOrderStateIsShipped() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);

        OrderResponse delivered = new OrderResponse(
                10L, 1L, OrderStatus.DELIVERED, java.math.BigDecimal.valueOf(100),
                java.time.LocalDateTime.now(), java.util.List.of()
        );
        when(service.deliverOrder(10L)).thenReturn(delivered);

        ResponseEntity<ApiResponse<OrderResponse>> response = controller.deliver(10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals(OrderStatus.DELIVERED, response.getBody().getData().getStatus());
        verify(service).deliverOrder(10L);
    }

    @Test
    void adminDeliveryOperation_failsWhenOrderStateIsInvalid() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);

        when(service.deliverOrder(10L)).thenThrow(new BusinessException(
                "Cannot deliver order with status: CONFIRMED. Only SHIPPED orders can transition to DELIVERED"));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.deliver(10L));
        assertTrue(ex.getMessage().contains("Only SHIPPED orders can transition to DELIVERED"));
    }
}
