package com.blackroth.training.productservice.controller;

import com.blackroth.training.productservice.dto.*;
import com.blackroth.training.productservice.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> all() {
        return ResponseEntity.ok(ApiResponse.success("Products fetched successfully", service.getAll()));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> categories() {
        return ResponseEntity.ok(ApiResponse.success("Categories fetched successfully", service.getCategories()));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(@Valid @RequestBody CategoryRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Category created successfully", service.createCategory(r.name())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Product fetched successfully", service.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody ProductRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Product created successfully", service.create(r)));
    }
}
