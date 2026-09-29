package com.blackroth.training.mobilebackend.repository;

import com.blackroth.training.mobilebackend.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
