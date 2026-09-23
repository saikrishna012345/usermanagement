package com.blackroth.training.productservice.service;

import com.blackroth.training.productservice.dto.*;
import com.blackroth.training.productservice.exception.*;
import com.blackroth.training.productservice.model.*;
import com.blackroth.training.productservice.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {
    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductService(ProductRepository p, CategoryRepository c) {
        products = p;
        categories = c;
    }

    @Transactional
    public ProductResponse create(ProductRequest r) {
        Category c = categories.findById(r.getCategoryId()).orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + r.getCategoryId()));
        return toResponse(products.save(new Product(r.getName(), r.getDescription(), r.getPrice(), r.getStockQuantity(), c)));
    }

    public ProductResponse get(Long id) {
        return toResponse(products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id)));
    }

    public List<ProductResponse> getAll() {
        return products.findAll().stream().map(this::toResponse).toList();
    }

    public CategoryResponse createCategory(String name) {
        return toCategoryResponse(categories.save(new Category(name)));
    }

    public List<CategoryResponse> getCategories() {
        return categories.findAll().stream().map(this::toCategoryResponse).toList();
    }

    private CategoryResponse toCategoryResponse(Category c) {
        return new CategoryResponse(c.getId(), c.getName());
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getStockQuantity(), p.getCategory().getName());
    }
}
