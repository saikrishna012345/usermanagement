package com.company.mobilebackend.repository;

import com.company.mobilebackend.dto.OrderStatisticsResponse;
import com.company.mobilebackend.dto.TopCustomerResponse;
import com.company.mobilebackend.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    List<Order> findByUserId(Long userId);

    List<Order> findByStatus(String status);

    @Query("SELECT o FROM Order o WHERE o.totalAmount > :threshold")
    List<Order> findHighValueOrders(@Param("threshold") BigDecimal threshold);

    @Query("SELECT o FROM Order o WHERE o.totalAmount > :threshold")
    Page<Order> findHighValueOrdersPaged(@Param("threshold") BigDecimal threshold, Pageable pageable);

    @Query("SELECT new com.company.mobilebackend.dto.TopCustomerResponse(" +
            "o.user.id, o.user.firstName, o.user.lastName, COUNT(o), SUM(o.totalAmount)) " +
            "FROM Order o " +
            "GROUP BY o.user.id, o.user.firstName, o.user.lastName " +
            "ORDER BY SUM(o.totalAmount) DESC")
    List<TopCustomerResponse> findTopCustomers(Pageable pageable);

    @Query("SELECT new com.company.mobilebackend.dto.OrderStatisticsResponse(" +
            "COUNT(o), COALESCE(SUM(o.totalAmount), 0), COALESCE(AVG(o.totalAmount), 0.0)) " +
            "FROM Order o")
    OrderStatisticsResponse getOrderStatistics();

    @Query(value =
            "SELECT c.name AS category_name, SUM(oi.quantity * oi.unit_price) AS revenue " +
                    "FROM order_items oi " +
                    "JOIN products p ON oi.product_id = p.id " +
                    "JOIN categories c ON p.category_id = c.id " +
                    "GROUP BY c.name " +
                    "ORDER BY revenue DESC",
            nativeQuery = true)
    List<Object[]> findRevenueByCategory();
}