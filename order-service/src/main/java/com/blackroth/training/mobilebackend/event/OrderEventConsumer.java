package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ProcessedEventRepository processedEventRepository;

    public OrderEventConsumer(
            ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(
            topics = "order.created",
            groupId = "order-service"
    )
    @Transactional
    public void consumeOrderCreated(OrderCreatedEvent event) {
        long startTime = System.currentTimeMillis();

        if (event == null) {
            throw new IllegalArgumentException("Order event must not be null");
        }

        event.validate();

        try {
            if (event.correlationId() != null && !event.correlationId().isBlank()) {
                MDC.put("correlationId", event.correlationId());
            }
            MDC.put("eventId", event.eventId());
            MDC.put("orderId", String.valueOf(event.orderId()));
            MDC.put("eventType", event.eventType());

            log.info("Received event {} for order {}", event.eventId(), event.orderId());

            if (processedEventRepository.existsByEventId(event.eventId())) {
                log.warn("Duplicate event skipped: eventId={}, orderId={}, durationMs={}",
                        event.eventId(), event.orderId(), System.currentTimeMillis() - startTime);
                return;
            }

            processedEventRepository.saveAndFlush(
                    new ProcessedEvent(
                            event.eventId(),
                            event.eventType()
                    )
            );

            log.info("Successfully processed order event: eventId={}, orderId={}, durationMs={}",
                    event.eventId(), event.orderId(), System.currentTimeMillis() - startTime);

        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
            MDC.remove("orderId");
            MDC.remove("eventType");
        }
    }
}