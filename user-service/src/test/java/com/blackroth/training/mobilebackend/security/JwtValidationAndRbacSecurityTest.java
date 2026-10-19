package com.blackroth.training.mobilebackend.security;

import com.blackroth.training.mobilebackend.dto.UserRequest;
import com.blackroth.training.mobilebackend.dto.UserResponse;
import com.blackroth.training.mobilebackend.model.User;
import com.blackroth.training.mobilebackend.repository.UserRepository;
import com.blackroth.training.mobilebackend.service.JwtService;
import com.blackroth.training.mobilebackend.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtValidationAndRbacSecurityTest {

    private static final String TEST_SECRET = "test-secret-key-for-user-service-min-256-bits-long-ok";
    private JwtService jwtService;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, 900000L, 604800000L);
        signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void extractUserId_withIntegerClaim_succeedsWithoutTypeCastException() {
        String token = Jwts.builder()
                .subject("user@test.com")
                .claim("userId", 42) // Integer in JSON
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        Long userId = jwtService.extractUserId(token);
        assertEquals(42L, userId);
    }

    @Test
    void extractUserId_withLongClaim_succeeds() {
        String token = jwtService.generateAccessToken(100L, "user@test.com", "USER");
        Long userId = jwtService.extractUserId(token);
        assertEquals(100L, userId);
    }

    @Test
    void extractUserId_withNumericStringClaim_succeeds() {
        String token = Jwts.builder()
                .subject("user@test.com")
                .claim("userId", "200")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        Long userId = jwtService.extractUserId(token);
        assertEquals(200L, userId);
    }

    @Test
    void extractUserId_withInvalidNumericClaim_returnsNullSafely() {
        String token = Jwts.builder()
                .subject("user@test.com")
                .claim("userId", "not-a-number")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(signingKey)
                .compact();

        Long userId = jwtService.extractUserId(token);
        assertNull(userId);
    }

    @Test
    void extractRole_normalizesRoleNames() {
        String token1 = jwtService.generateAccessToken(1L, "admin@test.com", "ROLE_ADMIN");
        assertEquals("ADMIN", jwtService.extractRole(token1));

        String token2 = jwtService.generateAccessToken(2L, "user@test.com", "user");
        assertEquals("USER", jwtService.extractRole(token2));
    }

    @Test
    void expiredToken_failsValidation() {
        String expiredToken = jwtService.generateAccessToken(1L, "user@test.com", "USER");
        // create expired token explicitly
        String token = Jwts.builder()
                .subject("user@test.com")
                .claim("role", "USER")
                .issuedAt(new Date(System.currentTimeMillis() - 20000))
                .expiration(new Date(System.currentTimeMillis() - 5000))
                .signWith(signingKey)
                .compact();

        assertFalse(jwtService.isTokenValid(token, "user@test.com"));
        assertTrue(jwtService.isTokenExpired(token));
    }

    @Test
    void userSecurity_blocksUserFromAccessingAnotherUserProfile() {
        UserRepository userRepository = mock(UserRepository.class);
        UserSecurity userSecurity = new UserSecurity(userRepository);

        User victimUser = new User();
        victimUser.setId(2L);
        victimUser.setEmail("victim@test.com");
        when(userRepository.findById(2L)).thenReturn(Optional.of(victimUser));

        var attackerAuth = new UsernamePasswordAuthenticationToken(
                "attacker@test.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        boolean allowed = userSecurity.isCurrentUserOrAdmin(attackerAuth, 2L);
        assertFalse(allowed, "User must not access another user's profile");
    }

    @Test
    void userSecurity_allowsUserToAccessOwnProfile() {
        UserRepository userRepository = mock(UserRepository.class);
        UserSecurity userSecurity = new UserSecurity(userRepository);

        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@test.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        var ownerAuth = new UsernamePasswordAuthenticationToken(
                "owner@test.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        boolean allowed = userSecurity.isCurrentUserOrAdmin(ownerAuth, 1L);
        assertTrue(allowed, "User must be allowed to access own profile");
    }

    @Test
    void userSecurity_allowsAdminToAccessAnyProfile() {
        UserRepository userRepository = mock(UserRepository.class);
        UserSecurity userSecurity = new UserSecurity(userRepository);

        var adminAuth = new UsernamePasswordAuthenticationToken(
                "admin@test.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        boolean allowed = userSecurity.isCurrentUserOrAdmin(adminAuth, 999L);
        assertTrue(allowed, "Admin must be allowed to access any user profile");
    }

    @Test
    void updateUser_regularUserCannotEscalateRole() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserService userService = new UserService(userRepository, passwordEncoder);

        User target = new User();
        target.setId(1L);
        target.setEmail("user@test.com");
        target.setRole("USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        // Authenticate as regular USER
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );

        UserRequest request = new UserRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setEmail("user@test.com");
        request.setMobileNumber("1234567890");
        request.setRole("ADMIN"); // Escalation attempt!

        UserResponse response = userService.updateUser(1L, request);
        assertEquals("USER", response.getRole(), "Role escalation attempt must be ignored");
        assertEquals("USER", target.getRole());
    }

    @Test
    void updateUser_adminCanUpdateRole() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserService userService = new UserService(userRepository, passwordEncoder);

        User target = new User();
        target.setId(1L);
        target.setEmail("user@test.com");
        target.setRole("USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        // Authenticate as ADMIN
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );

        UserRequest request = new UserRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setEmail("user@test.com");
        request.setMobileNumber("1234567890");
        request.setRole("ADMIN");

        UserResponse response = userService.updateUser(1L, request);
        assertEquals("ADMIN", response.getRole(), "Admin must be able to change user role");
    }
}
