package com.blackroth.training.productservice.repository;

import com.blackroth.training.productservice.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
