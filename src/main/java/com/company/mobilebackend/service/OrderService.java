package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.OrderItemRequest;
import com.company.mobilebackend.dto.OrderItemResponse;
import com.company.mobilebackend.dto.OrderRequest;
import com.company.mobilebackend.dto.OrderResponse;
import com.company.mobilebackend.exception.BusinessException;
import com.company.mobilebackend.exception.ResourceNotFoundException;
import com.company.mobilebackend.model.Order;
import com.company.mobilebackend.model.OrderItem;
import com.company.mobilebackend.model.OrderStatus;
import com.company.mobilebackend.model.Product;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.OrderRepository;
import com.company.mobilebackend.repository.ProductRepository;
import com.company.mobilebackend.repository.UserRepository;
import com.company.mobilebackend.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
                        ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.getUserId()));

        Order order = new Order(user, OrderStatus.PENDING, BigDecimal.ZERO);
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found with id: " + itemRequest.getProductId()));

            if (product.getStockQuantity() < itemRequest.getQuantity()) {
                throw new BusinessException("Insufficient stock for product: " + product.getName());
            }

            product.setStockQuantity(product.getStockQuantity() - itemRequest.getQuantity());
            productRepository.save(product);

            OrderItem orderItem = new OrderItem(product, itemRequest.getQuantity(), product.getPrice());
            order.addOrderItem(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
        }

        order.setTotalAmount(total);
        Order savedOrder = orderRepository.save(order);
        return toResponse(savedOrder);
    }

    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));

        String currentEmail = SecurityUtils.getCurrentUserEmail();
        boolean isOwner = order.getUser().getEmail().equals(currentEmail);
        boolean isAdmin = isCurrentUserAdmin();

        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("You do not have permission to view this order");
        }

        return toResponse(order);
    }

    public List<OrderResponse> getMyOrders() {
        String currentEmail = SecurityUtils.getCurrentUserEmail();
        User user = userRepository.findByEmail(currentEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return orderRepository.findByUserId(user.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<OrderResponse> getOrdersByUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return orderRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public OrderResponse cancelOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));

        String currentEmail = SecurityUtils.getCurrentUserEmail();
        boolean isOwner = order.getUser().getEmail().equals(currentEmail);

        if (!isOwner) {
            throw new AccessDeniedException("You can only cancel your own orders");
        }

        if (OrderStatus.CANCELLED.equals(order.getStatus())) {
            throw new BusinessException("Order is already cancelled");
        }

        for (OrderItem item : order.getOrderItems()) {
            Product product = item.getProduct();
            product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
            productRepository.save(product);
        }

        order.setStatus(OrderStatus.CANCELLED);
        Order updated = orderRepository.save(order);
        return toResponse(updated);
    }

    private boolean isCurrentUserAdmin() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getOrderItems().stream()
                .map(item -> new OrderItemResponse(
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