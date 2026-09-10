package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.PagedResponse;
import com.company.mobilebackend.dto.ProductRequest;
import com.company.mobilebackend.dto.ProductResponse;
import com.company.mobilebackend.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.createProduct(request);
        ApiResponse<ProductResponse> response = ApiResponse.success("Product created successfully", created);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProductResponse>>> getProducts( 
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 10) Pageable pageable) {
        PagedResponse<ProductResponse> products = productService.filterProducts(category, minPrice, maxPrice, pageable);
        ApiResponse<PagedResponse<ProductResponse>> response = ApiResponse.success("Products fetched successfully", products);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductById(@PathVariable Long id) {
        ProductResponse product = productService.getProductById(id);
        ApiResponse<ProductResponse> response = ApiResponse.success("Product fetched successfully", product);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        ProductResponse updated = productService.updateProduct(id, request);
        ApiResponse<ProductResponse> response = ApiResponse.success("Product updated successfully", updated);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> searchProducts(@RequestParam String name) {
        List<ProductResponse> products = productService.searchProducts(name);
        ApiResponse<List<ProductResponse>> response = ApiResponse.success("Products fetched successfully", products);
        return ResponseEntity.ok(response);
    }
}