package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentAuthorizationService {

    private final boolean simulateFailure;

    public PaymentAuthorizationService(
            @Value("${saga.payment.simulate-failure:false}")
            boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    public void authorize(Long orderId) {
        if (simulateFailure) {
            throw new BusinessException(
                    "Payment authorization failed for order: " + orderId
            );
        }
    }
}