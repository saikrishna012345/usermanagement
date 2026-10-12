
package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventConsumer {

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
        if (event == null || event.eventId() == null
                || event.eventId().isBlank()) {
            throw new IllegalArgumentException(
                    "Order event must contain an eventId");
        }

        if (processedEventRepository.existsByEventId(event.eventId())) {
            System.out.println(
                    "Duplicate event skipped: " + event.eventId());
            return;
        }

        try {
            processedEventRepository.saveAndFlush(
                    new ProcessedEvent(
                            event.eventId(),
                            event.eventType()
                    )
            );

            System.out.println(
                    "Processed order event: " + event.eventId()
                            + ", type: " + event.eventType()
            );
        } catch (DataIntegrityViolationException exception) {
            System.out.println(
                    "Duplicate event rejected by database: "
                            + event.eventId()
            );
        }
    }
}
