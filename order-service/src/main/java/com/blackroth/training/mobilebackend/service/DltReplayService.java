package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
public class DltReplayService {

    private static final Logger log = LoggerFactory.getLogger(DltReplayService.class);

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final ConsumerFactory<String, OrderCreatedEvent> consumerFactory;

    public DltReplayService(
            KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
            ConsumerFactory<String, OrderCreatedEvent> consumerFactory) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
    }

    public boolean replayEvent(OrderCreatedEvent event) {
        if (event == null || event.eventId() == null) {
            throw new IllegalArgumentException("Cannot replay null event or event without eventId");
        }

        try {
            log.info("Replaying event {} for order {} from DLT to order.created",
                    event.eventId(), event.orderId());
            kafkaTemplate.send("order.created", String.valueOf(event.orderId()), event)
                    .get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.error("Failed to replay event {} to order.created: {}", event.eventId(), e.getMessage(), e);
            return false;
        }
    }
}
