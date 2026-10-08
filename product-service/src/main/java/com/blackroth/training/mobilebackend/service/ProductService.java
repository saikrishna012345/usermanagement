package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.InvalidRequestException;
import com.blackroth.training.mobilebackend.dto.PagedResponse;
import com.blackroth.training.mobilebackend.dto.ProductRequest;
import com.blackroth.training.mobilebackend.dto.ProductResponse;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Category;
import com.blackroth.training.mobilebackend.model.Product;
import com.blackroth.training.mobilebackend.repository.CategoryRepository;
import com.blackroth.training.mobilebackend.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @CacheEvict(value = {"products", "productsList"}, allEntries = true)
    public ProductResponse createProduct(ProductRequest request) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        Product product = new Product(request.getName(), request.getDescription(),
                request.getPrice(), request.getStockQuantity(), category);
        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    @Cacheable(value = "products", key = "#p0")
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findByIdWithCategory(id);

        if (product == null) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }

        return toResponse(product);
    }

    @CacheEvict(value = {"products", "productsList"}, key = "#p0", allEntries = true)
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStockQuantity(request.getStockQuantity());
        product.setCategory(category);

        Product updated = productRepository.save(product);
        return toResponse(updated);
    }

    @CacheEvict(value = {"products", "productsList"}, allEntries = true)
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }
        productRepository.deleteById(id);
    }

    public List<ProductResponse> searchProducts(String name) {
        return productRepository.findByNameContainingIgnoreCase(name)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "productsList", key = "'all'", condition = "#category == null && #minPrice == null && #maxPrice == null")
    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> filterProducts(Long categoryId, BigDecimal minPrice,
                                                         BigDecimal maxPrice, Pageable pageable) {
        Page<Product> page = productRepository.filterProducts(categoryId, minPrice, maxPrice, pageable);
        List<ProductResponse> content = page.getContent().stream().map(this::toResponse).collect(Collectors.toList());
        return new PagedResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    @org.springframework.transaction.annotation.Transactional
    public ProductResponse decreaseStock(Long id, Integer quantity) {
        Product product = productRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        if (quantity == null || quantity < 1) throw new InvalidRequestException("quantity must be at least 1");
        if (product.getStockQuantity() < quantity) throw new BusinessException("Insufficient stock for product: " + product.getName());
        product.setStockQuantity(product.getStockQuantity() - quantity);
        return toResponse(productRepository.save(product));
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(),
                p.getPrice(), p.getStockQuantity(), p.getCategory().getName());
    }
}