package com.company.mobilebackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Async
    public CompletableFuture<Void> sendOrderConfirmation(Long orderId, String userEmail) {
        log.info("Sending order confirmation for orderId={} to email={}", orderId, userEmail);

        try {
            Thread.sleep(2000); // simulates a slow external call (email/SMS provider)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Order confirmation sent successfully for orderId={}", orderId);
        return CompletableFuture.completedFuture(null);
    }

    @Async
    public CompletableFuture<Void> generateOrderConfirmationDocument(Long orderId) {
        log.info("Generating confirmation document for orderId={}", orderId);

        try {
            Thread.sleep(1500); // simulates document generation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Confirmation document generated for orderId={}", orderId);
        return CompletableFuture.completedFuture(null);
    }
}