package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
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
}
