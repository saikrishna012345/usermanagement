package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.PagedResponse;
import com.company.mobilebackend.dto.UserRequest;
import com.company.mobilebackend.dto.UserResponse;
import com.company.mobilebackend.exception.DuplicateUserException;
import com.company.mobilebackend.exception.UserNotFoundException;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User existingUser;
    private UserRequest validRequest;

    @BeforeEach
    void setUp() {
        existingUser = new User("Sai", "Krishna", "sai@example.com", "9876543210",
                "hashed_secret123", "USER", "ACTIVE");
        existingUser.setId(1L);

        validRequest = new UserRequest();
        validRequest.setFirstName("Sai");
        validRequest.setLastName("Krishna");
        validRequest.setEmail("sai@example.com");
        validRequest.setMobileNumber("9876543210");
        validRequest.setPassword("secret123");
        validRequest.setStatus("ACTIVE");
    }

    @Test
    void createUser_savesSuccessfully_whenNoDuplicates() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(false);
        when(userRepository.existsByMobileNumber(validRequest.getMobileNumber())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_secret123");
        when(userRepository.save(any(User.class))).thenReturn(existingUser);

        UserResponse response = userService.createUser(validRequest);

        assertThat(response.getEmail()).isEqualTo("sai@example.com");
        assertThat(response.getRole()).isEqualTo("USER");
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void createUser_throwsDuplicateUserException_whenEmailExists() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(true);

        assertThrows(DuplicateUserException.class, () -> userService.createUser(validRequest));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createUser_throwsDuplicateUserException_whenMobileNumberExists() {
        when(userRepository.existsByEmail(validRequest.getEmail())).thenReturn(false);
        when(userRepository.existsByMobileNumber(validRequest.getMobileNumber())).thenReturn(true);

        assertThrows(DuplicateUserException.class, () -> userService.createUser(validRequest));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getUserById_returnsUser_whenExists() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponse response = userService.getUserById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getFirstName()).isEqualTo("Sai");
    }

    @Test
    void getUserById_throwsUserNotFoundException_whenMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.getUserById(99L));
    }

    @Test
    void updateUser_updatesFields_withoutTouchingPassword_whenPasswordOmitted() {
        UserRequest updateRequest = new UserRequest();
        updateRequest.setFirstName("Updated");
        updateRequest.setLastName("Name");
        updateRequest.setEmail("updated@example.com");
        updateRequest.setMobileNumber("9999999999");
        updateRequest.setStatus("INACTIVE");

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);

        userService.updateUser(1L, updateRequest);

        assertThat(existingUser.getFirstName()).isEqualTo("Updated");
        assertThat(existingUser.getPasswordHash()).isEqualTo("hashed_secret123");
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, times(1)).save(existingUser);
    }

    @Test
    void updateUser_rehashesPassword_whenPasswordProvided() {
        UserRequest updateRequest = new UserRequest();
        updateRequest.setFirstName("Sai");
        updateRequest.setLastName("Krishna");
        updateRequest.setEmail("sai@example.com");
        updateRequest.setMobileNumber("9876543210");
        updateRequest.setPassword("newpassword123");
        updateRequest.setStatus("ACTIVE");

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode("newpassword123")).thenReturn("hashed_newpassword123");
        when(userRepository.save(any(User.class))).thenReturn(existingUser);

        userService.updateUser(1L, updateRequest);

        assertThat(existingUser.getPasswordHash()).isEqualTo("hashed_newpassword123");
        verify(passwordEncoder, times(1)).encode("newpassword123");
    }

    @Test
    void updateUser_throwsUserNotFoundException_whenMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.updateUser(99L, validRequest));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deleteUser_deletesSuccessfully_whenExists() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.deleteUser(1L);

        verify(userRepository, times(1)).deleteById(1L);
    }

    @Test
    void deleteUser_throwsUserNotFoundException_whenMissing() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> userService.deleteUser(99L));
        verify(userRepository, never()).deleteById(99L);
    }

    @Test
    void searchUsers_returnsMatchingUsers() {
        when(userRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("sai", "sai"))
                .thenReturn(List.of(existingUser));

        List<UserResponse> results = userService.searchUsers("sai");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFirstName()).isEqualTo("Sai");
    }

    @Test
    void getAllUsers_returnsPagedResponse() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(existingUser), pageable, 1);
        when(userRepository.findAll(pageable)).thenReturn(page);

        PagedResponse<UserResponse> response = userService.getAllUsers(pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.isLast()).isTrue();
    }
}