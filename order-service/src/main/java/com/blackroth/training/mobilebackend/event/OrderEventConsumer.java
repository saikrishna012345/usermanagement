package com.blackroth.training.mobilebackend.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {

    @KafkaListener(
            topics = "order.created",
            groupId = "order-service"
    )
    public void consumeOrderCreated(OrderCreatedEvent event) {
        System.out.println(
                "Received OrderCreatedEvent: " + event
        );
    }
}