package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DltReplayServiceTest {

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    private DltReplayService dltReplayService;

    @BeforeEach
    void setUp() {
        dltReplayService = new DltReplayService(kafkaTemplate, null);
    }

    @Test
    @DisplayName("Replay success publishes to order.created and returns true")
    void testReplaySuccessReturnsTrue() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dlt-1", "ORDER_CREATED", "1.0", Instant.now(), "corr-dlt-1", "100", 100L, 1L, new BigDecimal("49.99")
        );

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("100"), any(OrderCreatedEvent.class))).thenReturn(future);

        boolean result = dltReplayService.replayEvent(event);

        assertTrue(result);
        verify(kafkaTemplate).send(eq("order.created"), eq("100"), eq(event));
    }

    @Test
    @DisplayName("Replay failure when Kafka send fails returns false")
    void testReplayFailureReturnsFalse() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dlt-fail", "ORDER_CREATED", "1.0", Instant.now(), "corr-dlt-fail", "200", 200L, 1L, new BigDecimal("99.99")
        );

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka send timeout"));
        when(kafkaTemplate.send(eq("order.created"), eq("200"), any(OrderCreatedEvent.class))).thenReturn(failedFuture);

        boolean result = dltReplayService.replayEvent(event);

        assertFalse(result);
        verify(kafkaTemplate).send(eq("order.created"), eq("200"), eq(event));
    }

    @Test
    @DisplayName("Replay throws IllegalArgumentException when event is null")
    void testReplayNullEventThrows() {
        assertThrows(IllegalArgumentException.class, () -> dltReplayService.replayEvent(null));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Replay throws IllegalArgumentException when eventId is null")
    void testReplayNullEventIdThrows() {
        OrderCreatedEvent eventWithNullId = new OrderCreatedEvent(
                null, "ORDER_CREATED", "1.0", Instant.now(), "corr-1", "300", 300L, 1L, new BigDecimal("10.00")
        );

        assertThrows(IllegalArgumentException.class, () -> dltReplayService.replayEvent(eventWithNullId));
        verifyNoInteractions(kafkaTemplate);
    }
}
