
package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);
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
        long startTime = System.currentTimeMillis();
        String correlationId = null;

        try {
            OrderCreatedEvent payload = objectMapper.readValue(
                    event.getPayload(),
                    OrderCreatedEvent.class
            );

            correlationId = CorrelationIdUtils.resolveCorrelationId(
                    payload != null ? payload.correlationId() : null
            );

            MDC.put(CorrelationIdUtils.MDC_KEY, correlationId);
            MDC.put("service", "order-service");
            MDC.put("eventId", event.getEventId());
            MDC.put("eventType", event.getEventType());
            MDC.put("orderId", event.getAggregateId());
            MDC.put("topic", event.getTopic());
            MDC.put("attempt", String.valueOf(event.getAttempts() + 1));

            kafkaTemplate.send(
                    event.getTopic(),
                    event.getAggregateId(),
                    payload
            ).get(10, TimeUnit.SECONDS);

            long durationMs = System.currentTimeMillis() - startTime;
            int priorAttempts = event.getAttempts();

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            event.setLastError(null);

            outboxRepository.save(event);

            if (priorAttempts > 0) {
                log.info("Outbox event published successfully after retry: eventId={}, eventType={}, topic={}, orderId={}, attempt={}, durationMs={}, outcome=PUBLISHED",
                        event.getEventId(), event.getEventType(), event.getTopic(), event.getAggregateId(), priorAttempts + 1, durationMs);
            } else {
                log.info("Outbox event published successfully: eventId={}, eventType={}, topic={}, orderId={}, attempt=1, durationMs={}, outcome=PUBLISHED",
                        event.getEventId(), event.getEventType(), event.getTopic(), event.getAggregateId(), durationMs);
            }

        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startTime;
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);

            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }

            String sanitizedError = sanitizeErrorMessage(message);
            event.setLastError(sanitizedError);

            if (attempts >= MAX_ATTEMPTS) {
                event.setStatus("FAILED");
                outboxRepository.save(event);

                log.error("Outbox event publishing reached maximum attempts (terminal failure): eventId={}, eventType={}, topic={}, orderId={}, attempt={}/{}, durationMs={}, lastError={}, outcome=FAILED",
                        event.getEventId(), event.getEventType(), event.getTopic(), event.getAggregateId(), attempts, MAX_ATTEMPTS, durationMs, sanitizedError);
            } else {
                long delaySeconds = Math.min(60L, 1L << attempts);
                Instant nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
                event.setNextAttemptAt(nextAttemptAt);
                outboxRepository.save(event);

                log.warn("Outbox event publishing failed, scheduled for retry: eventId={}, eventType={}, topic={}, orderId={}, attempt={}/{}, nextAttemptAt={}, durationMs={}, lastError={}, outcome=RETRY_SCHEDULED",
                        event.getEventId(), event.getEventType(), event.getTopic(), event.getAggregateId(), attempts, MAX_ATTEMPTS, nextAttemptAt, durationMs, sanitizedError);
            }

        } finally {
            MDC.remove(CorrelationIdUtils.MDC_KEY);
            MDC.remove("service");
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("orderId");
            MDC.remove("topic");
            MDC.remove("attempt");
        }
    }

    private String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Unknown error";
        }
        String sanitized = rawMessage.replaceAll("(?i)(password|secret|token|pwd|authorization)=[^;\\s&]+", "$1=***");
        return sanitized.length() > 2000 ? sanitized.substring(0, 2000) : sanitized;
    }
}
