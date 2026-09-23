package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.ProductRequest;
import com.company.mobilebackend.dto.ProductResponse;
import com.company.mobilebackend.exception.ResourceNotFoundException;
import com.company.mobilebackend.model.Category;
import com.company.mobilebackend.model.Product;
import com.company.mobilebackend.repository.CategoryRepository;
import com.company.mobilebackend.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private Category category;
    private Product product;
    private ProductRequest request;

    @BeforeEach
    void setUp() {
        category = new Category("Electronics");
        category.setId(1L);

        product = new Product("Phone", "A smartphone", BigDecimal.valueOf(500), 10, category);
        product.setId(1L);

        request = new ProductRequest();
        request.setName("Phone");
        request.setDescription("A smartphone");
        request.setPrice(BigDecimal.valueOf(500));
        request.setStockQuantity(10);
        request.setCategoryId(1L);
    }

    @Test
    void createProduct_savesSuccessfully_whenCategoryExists() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductResponse response = productService.createProduct(request);

        assertThat(response.getName()).isEqualTo("Phone");
        assertThat(response.getCategoryName()).isEqualTo("Electronics");
        verify(productRepository, times(1)).save(any(Product.class));
    }

    @Test
    void createProduct_throwsResourceNotFoundException_whenCategoryMissing() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.createProduct(request));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void searchProducts_returnsMatchingProducts() {
        when(productRepository.findByNameContainingIgnoreCase("phone")).thenReturn(List.of(product));

        List<ProductResponse> results = productService.searchProducts("phone");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("Phone");
    }

    @Test
    void updateProduct_updatesFields_whenProductAndCategoryExist() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductRequest updateRequest = new ProductRequest();
        updateRequest.setName("Updated Phone");
        updateRequest.setDescription("Updated description");
        updateRequest.setPrice(BigDecimal.valueOf(600));
        updateRequest.setStockQuantity(5);
        updateRequest.setCategoryId(1L);

        productService.updateProduct(1L, updateRequest);

        assertThat(product.getName()).isEqualTo("Updated Phone");
        assertThat(product.getPrice()).isEqualTo(BigDecimal.valueOf(600));
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void updateProduct_throwsResourceNotFoundException_whenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.updateProduct(99L, request));
    }

    @Test
    void deleteProduct_deletesSuccessfully_whenExists() {
        when(productRepository.existsById(1L)).thenReturn(true);

        productService.deleteProduct(1L);

        verify(productRepository, times(1)).deleteById(1L);
    }

    @Test
    void deleteProduct_throwsResourceNotFoundException_whenMissing() {
        when(productRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> productService.deleteProduct(99L));
        verify(productRepository, never()).deleteById(99L);
    }
}