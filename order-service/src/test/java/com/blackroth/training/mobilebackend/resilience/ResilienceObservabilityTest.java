package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.client.ProductClientFallbackFactory;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.event.OrderEventConsumer;
import com.blackroth.training.mobilebackend.event.OutboxEventPublisher;
import com.blackroth.training.mobilebackend.exception.ProductServiceUnavailableException;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenTelemetry Tracing and Observability Chaos Resilience Tests")
class ResilienceObservabilityTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private Tracer tracer;

    @Mock
    private Span mockSpan;

    private ObjectMapper objectMapper;
    private OutboxEventPublisher publisher;
    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        publisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, objectMapper, tracer);
        ReflectionTestUtils.setField(publisher, "enabled", true);

        consumer = new OrderEventConsumer(processedEventRepository, tracer);
    }

    @Test
    @DisplayName("OpenFeign failures preserve cause and are diagnosable through fallback exceptions")
    void feignFailuresPreserveContextAndDiagnosability() {
        ProductClientFallbackFactory factory = new ProductClientFallbackFactory();
        RuntimeException rootCause = new RuntimeException("DNS resolution failed for product-service");

        ProductClient client = factory.create(rootCause);

        ProductServiceUnavailableException ex = assertThrows(
                ProductServiceUnavailableException.class,
                () -> client.getProduct(123L)
        );

        assertNotNull(ex.getCause());
        assertSame(rootCause, ex.getCause());
        assertTrue(ex.getMessage().contains("temporarily unavailable"));
    }

    @Test
    @DisplayName("Outbox publish failure records error on OpenTelemetry span and persists sanitized error in database")
    void outboxPublishFailureRecordsSpanErrorAndSanitizesSecrets() throws Exception {
        OrderCreatedEvent payload = new OrderCreatedEvent(
                "evt-otel-1",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-otel-1",
                801L,
                42L,
                new BigDecimal("199.99")
        );

        OutboxEvent event = new OutboxEvent();
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "createdAt", Instant.now().minusSeconds(10));
        event.setEventId("evt-otel-1");
        event.setEventType("ORDER_CREATED");
        event.setTopic("order.created");
        event.setAggregateId("801");
        event.setPayload(objectMapper.writeValueAsString(payload));
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setNextAttemptAt(Instant.now().minusSeconds(5));

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        CompletableFuture failedFuture = new CompletableFuture<>();
        // Simulate an exception string containing credentials in key=value format
        Exception sensitiveException = new TimeoutException("Kafka broker failure: token=secret-jwt-token-12345; password=supersecretpwd; secret=confidential");
        failedFuture.completeExceptionally(sensitiveException);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failedFuture);

        publisher.publishPendingEvents();

        // Verify span was marked with error
        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(mockSpan).error(errorCaptor.capture());
        assertSame(sensitiveException, errorCaptor.getValue().getCause());

        // Verify database lastError is sanitized and DOES NOT contain credentials
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertNotNull(saved.getLastError());
        assertFalse(saved.getLastError().contains("secret-jwt-token-12345"), "Sensitive JWT token must be masked!");
        assertFalse(saved.getLastError().contains("supersecretpwd"), "Sensitive password must be masked!");
        assertFalse(saved.getLastError().contains("confidential"), "Sensitive secret must be masked!");
        assertTrue(saved.getLastError().contains("token=***"));
        assertTrue(saved.getLastError().contains("password=***"));
        assertTrue(saved.getLastError().contains("secret=***"));
    }

    @Test
    @DisplayName("Kafka Consumer extracts W3C traceparent and tags consumer span with messaging semantics")
    void consumerPropagatesW3CTraceContextAndTagsSpan() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-otel-2",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-otel-2",
                802L,
                42L,
                new BigDecimal("49.99")
        );

        when(processedEventRepository.existsByEventId("evt-otel-2")).thenReturn(false);

        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        consumer.consumeOrderCreated(event, "order.created", 0, 200L, "corr-otel-2", traceparent);

        // Verify messaging span tags per OpenTelemetry semantic conventions
        verify(mockSpan).tag("messaging.system", "kafka");
        verify(mockSpan).tag("messaging.destination.name", "order.created");
        verify(mockSpan).tag("messaging.operation", "receive");
        verify(mockSpan).tag("messaging.kafka.consumer.group", "order-service");
        verify(mockSpan).tag("event.type", "ORDER_CREATED");
        verify(mockSpan).tag("order.id", "802");
    }

    @Test
    @DisplayName("Consumer span records error when non-retryable exception occurs")
    void consumerSpanRecordsErrorOnMalformedEvent() {
        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeOrderCreated(null, "order.created", 0, 201L, "corr-null", null)
        );

        verify(mockSpan).error(ex);
    }
}
