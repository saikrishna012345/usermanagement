package com.blackroth.training.mobilebackend.metrics;

import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventMetricsTest {

    private MeterRegistry meterRegistry;
    private OutboxEventRepository outboxEventRepository;
    private OrderRepository orderRepository;
    private EventMetrics eventMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        outboxEventRepository = mock(OutboxEventRepository.class);
        orderRepository = mock(OrderRepository.class);

        when(outboxEventRepository.countByStatus("PENDING")).thenReturn(5L);
        when(outboxEventRepository.countByStatus("FAILED")).thenReturn(1L);
        when(orderRepository.countByStatus(OrderStatus.COMPENSATION_PENDING)).thenReturn(2L);

        eventMetrics = new EventMetrics(meterRegistry, outboxEventRepository, orderRepository);
        eventMetrics.registerGauges();
    }

    @Test
    @DisplayName("Gauges correctly reflect database counts for pending/failed outbox and pending saga compensations")
    void testGaugesReflectDatabaseCounts() {
        Gauge pendingOutboxGauge = meterRegistry.find("events.outbox.pending").gauge();
        assertNotNull(pendingOutboxGauge);
        assertEquals(5.0, pendingOutboxGauge.value());

        Gauge failedOutboxGauge = meterRegistry.find("events.outbox.failed").gauge();
        assertNotNull(failedOutboxGauge);
        assertEquals(1.0, failedOutboxGauge.value());

        Gauge sagaPendingGauge = meterRegistry.find("saga.compensation.pending").gauge();
        assertNotNull(sagaPendingGauge);
        assertEquals(2.0, sagaPendingGauge.value());
    }

    @Test
    @DisplayName("Outbox published counter and duration timer record bounded tags and measurements")
    void testOutboxPublishedAndDuration() {
        eventMetrics.recordOutboxPublished("order.created", "OrderCreated", "published");
        eventMetrics.recordOutboxDuration("order.created", "OrderCreated", "published", 150L);

        Counter counter = meterRegistry.find("events.outbox.published")
                .tag("topic", "order_created")
                .tag("event_type", "ordercreated")
                .tag("outcome", "published")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        Timer timer = meterRegistry.find("events.outbox.duration")
                .tag("topic", "order_created")
                .tag("event_type", "ordercreated")
                .tag("outcome", "published")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 150.0);
    }

    @Test
    @DisplayName("Consumer processed counter and duration timer record outcomes accurately")
    void testConsumerProcessedAndDuration() {
        eventMetrics.recordConsumerProcessed("order.created", "OrderCreated", "success");
        eventMetrics.recordConsumerProcessed("order.created", "OrderCreated", "duplicate");
        eventMetrics.recordConsumerDuration("order.created", "OrderCreated", "success", 45L);

        Counter successCounter = meterRegistry.find("events.consumer.processed")
                .tag("topic", "order_created")
                .tag("outcome", "success")
                .counter();
        assertNotNull(successCounter);
        assertEquals(1.0, successCounter.count());

        Counter dupCounter = meterRegistry.find("events.consumer.processed")
                .tag("topic", "order_created")
                .tag("outcome", "duplicate")
                .counter();
        assertNotNull(dupCounter);
        assertEquals(1.0, dupCounter.count());

        Timer timer = meterRegistry.find("events.consumer.duration")
                .tag("topic", "order_created")
                .tag("outcome", "success")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    @DisplayName("Consumer retry, DLT routing, and DLT replay counters increment as expected")
    void testConsumerRetryDltAndReplay() {
        eventMetrics.recordConsumerRetry("order.created", "OrderCreated");
        eventMetrics.recordConsumerDlt("order.created", "OrderCreated", "validation_error");
        eventMetrics.recordDltReplay("order.created", "success");

        Counter retryCounter = meterRegistry.find("events.consumer.retries")
                .tag("topic", "order_created")
                .counter();
        assertNotNull(retryCounter);
        assertEquals(1.0, retryCounter.count());

        Counter dltCounter = meterRegistry.find("events.consumer.dlt")
                .tag("topic", "order_created")
                .tag("reason", "validation_error")
                .counter();
        assertNotNull(dltCounter);
        assertEquals(1.0, dltCounter.count());

        Counter replayCounter = meterRegistry.find("events.dlt.replay")
                .tag("topic", "order_created")
                .tag("outcome", "success")
                .counter();
        assertNotNull(replayCounter);
        assertEquals(1.0, replayCounter.count());
    }

    @Test
    @DisplayName("Saga compensation total counter records success, failure, and recovery outcomes")
    void testSagaCompensationMetrics() {
        eventMetrics.recordSagaCompensation("success");
        eventMetrics.recordSagaCompensation("failed");
        eventMetrics.recordSagaCompensation("recovered");

        Counter successCounter = meterRegistry.find("saga.compensation.total")
                .tag("outcome", "success")
                .counter();
        assertNotNull(successCounter);
        assertEquals(1.0, successCounter.count());

        Counter failedCounter = meterRegistry.find("saga.compensation.total")
                .tag("outcome", "failed")
                .counter();
        assertNotNull(failedCounter);
        assertEquals(1.0, failedCounter.count());

        Counter recoveredCounter = meterRegistry.find("saga.compensation.total")
                .tag("outcome", "recovered")
                .counter();
        assertNotNull(recoveredCounter);
        assertEquals(1.0, recoveredCounter.count());
    }

    @Test
    @DisplayName("Kafka consumer lag max returns 0.0 when not present or active gauge value when present")
    void testGetConsumerLagMax() {
        assertEquals(0.0, eventMetrics.getConsumerLagMax());

        Gauge.builder("kafka.consumer.fetch.manager.records.lag.max", () -> 42.0)
                .register(meterRegistry);

        assertEquals(42.0, eventMetrics.getConsumerLagMax());
    }

    @Test
    @DisplayName("Handles null MeterRegistry gracefully without throwing exceptions")
    void testNullMeterRegistrySafety() {
        EventMetrics noOpMetrics = new EventMetrics(null, null, null);
        assertDoesNotThrow(() -> {
            noOpMetrics.registerGauges();
            noOpMetrics.recordOutboxPublished("topic", "type", "success");
            noOpMetrics.recordOutboxDuration("topic", "type", "success", 10L);
            noOpMetrics.recordConsumerProcessed("topic", "type", "success");
            noOpMetrics.recordConsumerDuration("topic", "type", "success", 10L);
            noOpMetrics.recordConsumerRetry("topic", "type");
            noOpMetrics.recordConsumerDlt("topic", "type", "reason");
            noOpMetrics.recordDltReplay("topic", "success");
            noOpMetrics.recordSagaCompensation("recovered");
            assertEquals(0.0, noOpMetrics.getConsumerLagMax());
        });
    }
}
