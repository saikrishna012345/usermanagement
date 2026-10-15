package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private DltReplayService dltReplayService;

    @Test
    @DisplayName("Replays event to order.created topic successfully")
    void testReplayEventSuccess() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-replay",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-rep",
                500L,
                1L,
                new BigDecimal("99.00")
        );

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("500"), eq(event))).thenReturn(future);

        boolean result = dltReplayService.replayEvent(event);
        assertTrue(result);
        verify(kafkaTemplate).send("order.created", "500", event);
    }

    @Test
    @DisplayName("Rejects replaying null event or null eventId")
    void testReplayNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> dltReplayService.replayEvent(null));

        OrderCreatedEvent nullIdEvent = new OrderCreatedEvent(
                null, "ORDER_CREATED", "1.0", Instant.now(), "c", 1L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, () -> dltReplayService.replayEvent(nullIdEvent));
    }
}
