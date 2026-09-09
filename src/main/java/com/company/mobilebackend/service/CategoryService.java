package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.CategoryRequest;
import com.company.mobilebackend.dto.CategoryResponse;
import com.company.mobilebackend.dto.ProductResponse;
import com.company.mobilebackend.exception.ResourceNotFoundException;
import com.company.mobilebackend.model.Category;
import com.company.mobilebackend.model.Product;
import com.company.mobilebackend.repository.CategoryRepository;
import com.company.mobilebackend.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    public CategoryResponse createCategory(CategoryRequest request) {
        Category category = new Category(request.getName());
        Category saved = categoryRepository.save(category);
        return new CategoryResponse(saved.getId(), saved.getName());
    }

    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll()
                .stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName()))
                .collect(Collectors.toList());
    }

    public List<ProductResponse> getProductsByCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found with id: " + categoryId);
        }
        return productRepository.findByCategoryId(categoryId, org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .map(this::toProductResponse)
                .collect(Collectors.toList());
    }

    private ProductResponse toProductResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(),
                p.getPrice(), p.getStockQuantity(), p.getCategory().getName());
    }
}