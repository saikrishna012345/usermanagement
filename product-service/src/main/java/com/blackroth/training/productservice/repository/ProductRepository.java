package com.blackroth.training.productservice.repository;

import com.blackroth.training.productservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
