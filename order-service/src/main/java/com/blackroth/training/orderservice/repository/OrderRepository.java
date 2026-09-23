package com.blackroth.training.orderservice.repository;

import com.blackroth.training.orderservice.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
