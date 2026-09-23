package com.blackroth.training.productservice.config;

import com.blackroth.training.productservice.model.Category;
import com.blackroth.training.productservice.repository.CategoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {
    @Bean
    CommandLineRunner seedCategories(CategoryRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.save(new Category("Electronics"));
                repository.save(new Category("Home & Kitchen"));
                repository.save(new Category("Agriculture"));
            }
        };
    }
}
