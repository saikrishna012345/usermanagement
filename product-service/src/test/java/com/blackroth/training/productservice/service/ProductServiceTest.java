package com.blackroth.training.productservice.service;

import com.blackroth.training.productservice.model.*;
import com.blackroth.training.productservice.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductServiceTest {
    @Mock
    ProductRepository products;
    @Mock
    CategoryRepository categories;
    @InjectMocks
    ProductService service;

    ProductServiceTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void returnsProduct() {
        var category = new Category("Electronics");
        var product = new Product("Phone", "", new BigDecimal("100"), 5, category);
        when(products.findById(1L)).thenReturn(Optional.of(product));
        assertEquals("Phone", service.get(1L).name());
    }

    @Test
    void throwsWhenProductDoesNotExist() {
        when(products.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.blackroth.training.productservice.exception.ResourceNotFoundException.class, () -> service.get(1L));
    }
}
