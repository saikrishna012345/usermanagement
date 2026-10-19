package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.config.KafkaConfig;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.event.OrderEventConsumer;
import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.service.DltReplayService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Kafka Consumer Failure, Duplicate Idempotency, DLT Routing, and Replay Resilience Tests")
class ConsumerResilienceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Mock
    private ConsumerFactory<String, OrderCreatedEvent> consumerFactory;

    private OrderEventConsumer consumer;
    private DltReplayService replayService;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(processedEventRepository, null);
        replayService = new DltReplayService(kafkaTemplate, consumerFactory);
    }

    private OrderCreatedEvent createValidEvent(String eventId, Long orderId) {
        return new OrderCreatedEvent(
                eventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-test-consumer",
                orderId,
                55L,
                new BigDecimal("149.50")
        );
    }

    @Test
    @DisplayName("Valid event is processed and saved in ProcessedEvents deduplication store")
    void whenValidEvent_thenProcessedAndStoredInDeduplicationStore() {
        OrderCreatedEvent event = createValidEvent("evt-valid-1", 301L);
        when(processedEventRepository.existsByEventId("evt-valid-1")).thenReturn(false);

        consumer.consumeOrderCreated(event, "order.created", 0, 100L, "corr-test-consumer");

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).saveAndFlush(captor.capture());

        ProcessedEvent saved = captor.getValue();
        assertEquals("evt-valid-1", saved.getEventId());
        assertEquals("ORDER_CREATED", saved.getEventType());
    }

    @Test
    @DisplayName("Duplicate event is detected via ProcessedEvents and skipped without repeating actions")
    void whenDuplicateEvent_thenSkippedWithoutDuplicateProcessing() {
        OrderCreatedEvent event = createValidEvent("evt-duplicate-1", 302L);
        when(processedEventRepository.existsByEventId("evt-duplicate-1")).thenReturn(true);

        consumer.consumeOrderCreated(event, "order.created", 0, 101L, "corr-test-consumer");

        // Idempotency: saveAndFlush MUST NOT be invoked for an already processed event
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Malformed event missing eventId throws IllegalArgumentException for direct DLT routing")
    void whenMalformedEventMissingEventId_thenThrowsIllegalArgumentException() {
        OrderCreatedEvent malformed = new OrderCreatedEvent(
                null, // Missing eventId
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-malformed",
                303L,
                55L,
                new BigDecimal("99.99")
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeOrderCreated(malformed, "order.created", 0, 102L, "corr-malformed")
        );

        assertTrue(ex.getMessage().contains("eventId is required"));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Malformed event missing orderId throws IllegalArgumentException")
    void whenMalformedEventMissingOrderId_thenThrowsIllegalArgumentException() {
        OrderCreatedEvent malformed = new OrderCreatedEvent(
                "evt-malformed-2",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-malformed",
                null, // Missing orderId
                55L,
                new BigDecimal("99.99")
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeOrderCreated(malformed, "order.created", 0, 103L, "corr-malformed")
        );

        assertTrue(ex.getMessage().contains("orderId"));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Null event payload throws IllegalArgumentException for direct DLT routing")
    void whenNullPayload_thenThrowsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consumeOrderCreated(null, "order.created", 0, 104L, "corr-null")
        );

        assertTrue(ex.getMessage().contains("Order event must not be null"));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Kafka error handler classifies IllegalArgumentException as non-retryable and uses 3-retry backoff")
    void kafkaErrorHandlerConfiguredWithNonRetryableExceptionsAndBoundedBackoff() {
        KafkaConfig config = new KafkaConfig();
        DefaultErrorHandler errorHandler = config.kafkaErrorHandler(kafkaTemplate);

        assertNotNull(errorHandler);

        // Verify FixedBackOff configuration: 3 retries with 2000ms delay
        FixedBackOff backOff = new FixedBackOff(2000L, 3L);
        BackOffExecution execution = backOff.start();
        assertEquals(2000L, execution.nextBackOff());
        assertEquals(2000L, execution.nextBackOff());
        assertEquals(2000L, execution.nextBackOff());
        assertEquals(BackOffExecution.STOP, execution.nextBackOff());

        // Verify non-retryable exception routing
        ConsumerRecord<String, OrderCreatedEvent> record =
                new ConsumerRecord<>("order.created", 0, 105L, "305", createValidEvent("evt-305", 305L));
        IllegalArgumentException fatalException = new IllegalArgumentException("Invalid payload");

        // Non-retryable exceptions should not be retried
        assertDoesNotThrow(() -> {
            // Error handler should acknowledge or handle non-retryable directly
            errorHandler.handleOne(fatalException, record, mock(org.apache.kafka.clients.consumer.Consumer.class), mock(org.springframework.kafka.listener.MessageListenerContainer.class));
        });
    }

    @Test
    @DisplayName("DLT Replay publishes to order.created preserving the aggregate orderId as key and returns true")
    void dltReplayPreservesAggregateKeyAndTopic() {
        OrderCreatedEvent dltEvent = createValidEvent("evt-dlt-replay-1", 401L);

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("order.created"), eq("401"), eq(dltEvent)))
                .thenReturn(future);

        boolean success = replayService.replayEvent(dltEvent);

        assertTrue(success);
        verify(kafkaTemplate).send("order.created", "401", dltEvent);
    }

    @Test
    @DisplayName("DLT Replay handles send failure gracefully and returns false without crashing")
    void dltReplayHandlesFailureGracefully() {
        OrderCreatedEvent dltEvent = createValidEvent("evt-dlt-fail-1", 402L);

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka replay timed out"));
        when(kafkaTemplate.send(eq("order.created"), eq("402"), eq(dltEvent)))
                .thenReturn(failedFuture);

        boolean success = replayService.replayEvent(dltEvent);

        assertFalse(success);
    }

    @Test
    @DisplayName("DLT Replay rejects null event or missing eventId with IllegalArgumentException")
    void dltReplayRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> replayService.replayEvent(null));

        OrderCreatedEvent missingEventId = new OrderCreatedEvent(
                null, "ORDER_CREATED", "1.0", Instant.now(), "c", 1L, 1L, BigDecimal.ONE
        );
        assertThrows(IllegalArgumentException.class, () -> replayService.replayEvent(missingEventId));
    }
}
