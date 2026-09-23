package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.BestSellingProductResponse;
import com.company.mobilebackend.dto.OrderResponse;
import com.company.mobilebackend.dto.OrderStatisticsResponse;
import com.company.mobilebackend.dto.PagedResponse;
import com.company.mobilebackend.dto.TopCustomerResponse;
import com.company.mobilebackend.model.Order;
import com.company.mobilebackend.repository.OrderRepository;
import com.company.mobilebackend.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public AnalyticsService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    public PagedResponse<OrderResponse> getHighValueOrders(BigDecimal threshold, Pageable pageable) {
        Page<Order> page = orderRepository.findHighValueOrdersPaged(threshold, pageable);
        List<OrderResponse> content = page.getContent().stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());
        return new PagedResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    public List<TopCustomerResponse> getTopCustomers(int limit) {
        return orderRepository.findTopCustomers(PageRequest.of(0, limit));
    }

    public List<BestSellingProductResponse> getBestSellingProducts(int limit) {
        return productRepository.findBestSellingProducts(PageRequest.of(0, limit));
    }

    public OrderStatisticsResponse getOrderStatistics() {
        return orderRepository.getOrderStatistics();
    }

    private OrderResponse toOrderResponse(Order order) {
        List<com.company.mobilebackend.dto.OrderItemResponse> items = order.getOrderItems().stream()
                .map(item -> new com.company.mobilebackend.dto.OrderItemResponse(
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                ))
                .collect(Collectors.toList());

        return new OrderResponse(order.getId(), order.getUser().getId(), order.getStatus(),
                order.getTotalAmount(), order.getCreatedAt(), items);
    }
}