package com.blackroth.training.mobilebackend.metrics;

import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Centralized Micrometer metrics component for Event Operations and Observability.
 * Provides observable metrics for Kafka consumers, Outbox publishing, Saga compensation,
 * and DLT dead-letter management with bounded-cardinality tags.
 */
@Component
public class EventMetrics {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;
    private final OrderRepository orderRepository;

    public EventMetrics(
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Autowired(required = false) OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) OrderRepository orderRepository
    ) {
        this.meterRegistry = meterRegistry;
        this.outboxEventRepository = outboxEventRepository;
        this.orderRepository = orderRepository;
    }

    @PostConstruct
    public void registerGauges() {
        if (meterRegistry == null) {
            return;
        }

        if (outboxEventRepository != null) {
            Gauge.builder("events.outbox.pending", outboxEventRepository, repo -> repo.countByStatus("PENDING"))
                    .description("Current number of pending outbox events awaiting publication")
                    .register(meterRegistry);

            Gauge.builder("events.outbox.failed", outboxEventRepository, repo -> repo.countByStatus("FAILED"))
                    .description("Current number of permanently failed outbox events")
                    .register(meterRegistry);
        }

        if (orderRepository != null) {
            Gauge.builder("saga.compensation.pending", orderRepository, repo -> repo.countByStatus(OrderStatus.COMPENSATION_PENDING))
                    .description("Current number of orders awaiting Saga compensation recovery")
                    .register(meterRegistry);
        }
    }

    public void recordOutboxPublished(String topic, String eventType, String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("events.outbox.published")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .tag("outcome", sanitizeTag(outcome))
                .description("Total outbox events published by status")
                .register(meterRegistry)
                .increment();
    }

    public void recordOutboxDuration(String topic, String eventType, String outcome, long durationMs) {
        if (meterRegistry == null) return;
        Timer.builder("events.outbox.duration")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .tag("outcome", sanitizeTag(outcome))
                .description("Duration of outbox event publication in milliseconds")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordConsumerProcessed(String topic, String eventType, String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("events.consumer.processed")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .tag("outcome", sanitizeTag(outcome))
                .description("Total Kafka events processed by consumer")
                .register(meterRegistry)
                .increment();
    }

    public void recordConsumerDuration(String topic, String eventType, String outcome, long durationMs) {
        if (meterRegistry == null) return;
        Timer.builder("events.consumer.duration")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .tag("outcome", sanitizeTag(outcome))
                .description("Duration of Kafka consumer processing in milliseconds")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordConsumerRetry(String topic, String eventType) {
        if (meterRegistry == null) return;
        Counter.builder("events.consumer.retries")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .description("Total consumer retry attempts")
                .register(meterRegistry)
                .increment();
    }

    public void recordConsumerDlt(String topic, String eventType, String reason) {
        if (meterRegistry == null) return;
        Counter.builder("events.consumer.dlt")
                .tag("topic", sanitizeTag(topic))
                .tag("event_type", sanitizeTag(eventType))
                .tag("reason", sanitizeTag(reason))
                .description("Total events routed to dead-letter topics")
                .register(meterRegistry)
                .increment();
    }

    public void recordDltReplay(String topic, String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("events.dlt.replay")
                .tag("topic", sanitizeTag(topic))
                .tag("outcome", sanitizeTag(outcome))
                .description("Total dead-letter event replay attempts")
                .register(meterRegistry)
                .increment();
    }

    public void recordSagaCompensation(String outcome) {
        if (meterRegistry == null) return;
        Counter.builder("saga.compensation.total")
                .tag("outcome", sanitizeTag(outcome))
                .description("Total Saga compensation operations by outcome")
                .register(meterRegistry)
                .increment();
    }

    public double getConsumerLagMax() {
        if (meterRegistry == null) {
            return 0.0;
        }
        try {
            Gauge gauge = meterRegistry.find("kafka.consumer.fetch.manager.records.lag.max").gauge();
            return gauge != null ? gauge.value() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String sanitizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase().replace('.', '_').replace('-', '_');
    }
}
