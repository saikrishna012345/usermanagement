
package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxEventPublisher {

    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${outbox.publisher.enabled:true}")
    private boolean enabled;

    public OutboxEventPublisher(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:2000}")
    public void publishPendingEvents() {

        if (!enabled) {
            return;
        }

        List<OutboxEvent> events =
                outboxRepository
                        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                                "PENDING",
                                Instant.now()
                        );

        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {

        try {
            OrderCreatedEvent payload = objectMapper.readValue(
                    event.getPayload(),
                    OrderCreatedEvent.class
            );

            kafkaTemplate.send(
                    event.getTopic(),
                    event.getAggregateId(),
                    payload
            ).get(10, TimeUnit.SECONDS);

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            event.setLastError(null);

            outboxRepository.save(event);

        } catch (Exception exception) {

            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);

            String message = exception.getMessage();

            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }

            event.setLastError(
                    message.substring(0, Math.min(message.length(), 2000))
            );

            if (attempts >= MAX_ATTEMPTS) {
                event.setStatus("FAILED");
            } else {
                long delaySeconds = Math.min(60L, 1L << attempts);

                event.setNextAttemptAt(
                        Instant.now().plusSeconds(delaySeconds)
                );
            }

            outboxRepository.save(event);

            System.err.println(
                    "Outbox publication failed. Event ID: "
                            + event.getEventId()
                            + ", attempt: " + attempts
                            + ", status: " + event.getStatus()
            );
        }
    }
}
