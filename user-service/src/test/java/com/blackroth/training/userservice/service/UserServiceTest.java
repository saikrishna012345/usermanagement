package com.blackroth.training.userservice.service;

import com.blackroth.training.userservice.model.User;
import com.blackroth.training.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    @Mock
    UserRepository repo;
    @Mock
    PasswordEncoder encoder;
    @InjectMocks
    UserService service;

    UserServiceTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getsUser() {
        var u = new User("Sai", "Krishna", "sai@example.com", "9999999999", "hash", "USER", "ACTIVE");
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        assertEquals("sai@example.com", service.get(1L).email());
    }
}
