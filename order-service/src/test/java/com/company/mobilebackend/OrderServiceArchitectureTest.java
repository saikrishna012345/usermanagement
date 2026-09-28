package com.company.mobilebackend;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceArchitectureTest {
    @Test
    void orderServiceDoesNotContainProductRepository() {
        assertFalse(Files.exists(Paths.get("src/main/java/com/company/mobilebackend/repository/ProductRepository.java")));
    }
}
