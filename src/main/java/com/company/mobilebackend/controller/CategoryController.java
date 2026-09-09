package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.CategoryRequest;
import com.company.mobilebackend.dto.CategoryResponse;
import com.company.mobilebackend.dto.ProductResponse;
import com.company.mobilebackend.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categoryService.createCategory(request);
        ApiResponse<CategoryResponse> response = ApiResponse.success("Category created successfully", created);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories() {
        List<CategoryResponse> categories = categoryService.getAllCategories();
        ApiResponse<List<CategoryResponse>> response = ApiResponse.success("Categories fetched successfully", categories);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/products")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getProductsByCategory(@PathVariable Long id) {
        List<ProductResponse> products = categoryService.getProductsByCategory(id);
        ApiResponse<List<ProductResponse>> response = ApiResponse.success("Products fetched successfully", products);
        return ResponseEntity.ok(response);
    }
}