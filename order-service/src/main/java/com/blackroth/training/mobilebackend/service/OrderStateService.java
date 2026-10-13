package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderStateService {

    private final OrderRepository orderRepository;

    public OrderStateService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order savePendingOrder(Order order) {
        order.setStatus(OrderStatus.PENDING);
        return orderRepository.saveAndFlush(order);
    }

    @Transactional
    public Order markConfirmed(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.CONFIRMED);
        return orderRepository.saveAndFlush(order);
    }

    @Transactional
    public Order markCompensationPending(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.COMPENSATION_PENDING);
        return orderRepository.saveAndFlush(order);
    }

    @Transactional
    public Order markCancelled(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.saveAndFlush(order);
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Order not found with id: " + orderId
                        )
                );
    }
}