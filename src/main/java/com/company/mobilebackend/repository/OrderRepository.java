package com.company.mobilebackend.repository;

import com.company.mobilebackend.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);

    List<Order> findByStatus(String status);

    @Query("SELECT o FROM Order o WHERE o.totalAmount > :threshold")
    List<Order> findHighValueOrders(@Param("threshold") BigDecimal threshold);
}