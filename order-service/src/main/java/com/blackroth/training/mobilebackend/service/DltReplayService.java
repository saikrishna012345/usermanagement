package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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

        long startTime = System.currentTimeMillis();
        String correlationId = CorrelationIdUtils.resolveCorrelationId(event.correlationId());

        try {
            MDC.put(CorrelationIdUtils.MDC_KEY, correlationId);
            MDC.put("service", "order-service");
            MDC.put("eventId", event.eventId());
            if (event.orderId() != null) {
                MDC.put("orderId", String.valueOf(event.orderId()));
            }
            MDC.put("topic", "order.created");

            log.info("DLT replay initiated: eventId={}, orderId={}, targetTopic=order.created, outcome=REPLAY_ATTEMPTED",
                    event.eventId(), event.orderId());

            kafkaTemplate.send("order.created", String.valueOf(event.orderId()), event)
                    .get(5, TimeUnit.SECONDS);

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("DLT replay succeeded: eventId={}, orderId={}, targetTopic=order.created, durationMs={}, outcome=REPLAY_SUCCESS",
                    event.eventId(), event.orderId(), durationMs);
            return true;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("DLT replay failed: eventId={}, orderId={}, targetTopic=order.created, durationMs={}, error={}, outcome=REPLAY_FAILED",
                    event.eventId(), event.orderId(), durationMs, e.getMessage(), e);
            return false;

        } finally {
            MDC.remove(CorrelationIdUtils.MDC_KEY);
            MDC.remove("service");
            MDC.remove("eventId");
            MDC.remove("orderId");
            MDC.remove("topic");
        }
    }
}
