package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private OrderEventConsumer orderEventConsumer;

    @Test
    @DisplayName("Processes new event successfully and stores in processed_events")
    void testProcessNewEvent() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-123",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-abc",
                100L,
                1L,
                new BigDecimal("50.00")
        );

        when(processedEventRepository.existsByEventId("evt-123")).thenReturn(false);

        orderEventConsumer.consumeOrderCreated(event);

        verify(processedEventRepository).existsByEventId("evt-123");
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Skips duplicate event without double processing or saving")
    void testDuplicateEventSkipped() {
        OrderCreatedEvent duplicateEvent = new OrderCreatedEvent(
                "evt-duplicate",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-dup",
                100L,
                1L,
                new BigDecimal("50.00")
        );

        when(processedEventRepository.existsByEventId("evt-duplicate")).thenReturn(true);

        orderEventConsumer.consumeOrderCreated(duplicateEvent);

        verify(processedEventRepository).existsByEventId("evt-duplicate");
        verify(processedEventRepository, never()).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Rejects malformed event by throwing IllegalArgumentException for non-retryable DLT handling")
    void testMalformedEventFailsValidation() {
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(
                "",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-bad",
                100L,
                1L,
                new BigDecimal("50.00")
        );

        assertThrows(IllegalArgumentException.class, () -> orderEventConsumer.consumeOrderCreated(invalidEvent));
        verify(processedEventRepository, never()).existsByEventId(any());
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Processes new event successfully with topic, partition, offset, and correlation ID headers")
    void testProcessNewEventWithKafkaHeadersAndCorrelationId() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-hdr-1",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-payload",
                150L,
                1L,
                new BigDecimal("75.00")
        );

        when(processedEventRepository.existsByEventId("evt-hdr-1")).thenReturn(false);

        orderEventConsumer.consumeOrderCreated(event, "order.created", 0, 42L, "corr-from-header");

        verify(processedEventRepository).existsByEventId("evt-hdr-1");
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Processes new event successfully with W3C traceparent header")
    void testProcessNewEventWithTraceparentHeader() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-trace-1",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-payload-trace",
                160L,
                1L,
                new BigDecimal("85.00")
        );

        when(processedEventRepository.existsByEventId("evt-trace-1")).thenReturn(false);

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        orderEventConsumer.consumeOrderCreated(event, "order.created", 0, 43L, "corr-trace-header", traceparent);

        verify(processedEventRepository).existsByEventId("evt-trace-1");
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Missing or malformed traceparent header does not break event processing; valid fallback generated")
    void testMalformedTraceparentDoesNotBreakEventProcessing() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-malformed-trace",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-malformed",
                170L,
                1L,
                new BigDecimal("95.00")
        );

        when(processedEventRepository.existsByEventId("evt-malformed-trace")).thenReturn(false);

        String malformedTraceparent = "00-00000000000000000000000000000000-0000000000000000-01";
        assertDoesNotThrow(() -> orderEventConsumer.consumeOrderCreated(
                event, "order.created", 0, 44L, "corr-ok", malformedTraceparent));

        verify(processedEventRepository).existsByEventId("evt-malformed-trace");
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Rejects null event by throwing IllegalArgumentException for DLT routing")
    void testConsumeNullEventThrowsAndRejectsForDlt() {
        assertThrows(IllegalArgumentException.class,
                () -> orderEventConsumer.consumeOrderCreated(null, "order.created", 0, 10L, "corr-null", null));
        verify(processedEventRepository, never()).existsByEventId(any());
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Duplicate event with Kafka headers is skipped without re-saving")
    void testDuplicateEventWithHeadersSkipped() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dup-hdr",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-dup",
                250L,
                1L,
                new BigDecimal("120.00")
        );

        when(processedEventRepository.existsByEventId("evt-dup-hdr")).thenReturn(true);

        orderEventConsumer.consumeOrderCreated(event, "order.created", 1, 99L, "corr-dup", null);

        verify(processedEventRepository).existsByEventId("evt-dup-hdr");
        verify(processedEventRepository, never()).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Tracer starts and ends consumer span with messaging attributes when tracer is configured")
    void testConsumerWithTracerCreatesSpan() {
        Tracer mockTracer = mock(Tracer.class);
        Span mockSpan = mock(Span.class);
        Tracer.SpanInScope mockSpanInScope = mock(Tracer.SpanInScope.class);

        when(mockTracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockTracer.withSpan(mockSpan)).thenReturn(mockSpanInScope);

        OrderEventConsumer consumerWithTracer = new OrderEventConsumer(processedEventRepository, mockTracer);

        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-tracer-1",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-tracer",
                300L,
                1L,
                new BigDecimal("150.00")
        );

        when(processedEventRepository.existsByEventId("evt-tracer-1")).thenReturn(false);

        consumerWithTracer.consumeOrderCreated(event, "order.created", 0, 50L, "corr-tracer", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        verify(mockSpan).name("order.created receive");
        verify(mockSpan).tag("messaging.system", "kafka");
        verify(mockSpan).tag("messaging.destination.name", "order.created");
        verify(mockSpan).tag("messaging.operation", "receive");
        verify(mockSpan).tag("messaging.kafka.consumer.group", "order-service");
        verify(mockSpan).tag("event.type", "ORDER_CREATED");
        verify(mockSpan).tag("order.id", "300");
        verify(mockSpan).end();
        verify(mockSpanInScope).close();
    }
}
