package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        publisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(publisher, "enabled", true);
    }

    @Test
    @DisplayName("Publishes pending outbox event to Kafka and marks as PUBLISHED with timestamp")
    void testSuccessfulPublishMarksPublished() {
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                "evt-100",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-100",
                "100",
                100L,
                1L,
                new BigDecimal("99.99")
        );

        String payloadJson = assertDoesNotThrow(() -> objectMapper.writeValueAsString(eventPayload));

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId("evt-100");
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("100");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(0);
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("100"), any(OrderCreatedEvent.class))).thenReturn(future);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PUBLISHED", saved.getStatus());
        assertNotNull(saved.getPublishedAt());
        assertNull(saved.getLastError());
        assertEquals(0, saved.getAttempts());
    }

    @Test
    @DisplayName("Publication failure increments attempts, sets exponential backoff, and keeps status PENDING")
    void testKafkaFailureKeepsPendingWithBackoff() {
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                "evt-retry",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-retry",
                "200",
                200L,
                2L,
                new BigDecimal("150.00")
        );

        String payloadJson = assertDoesNotThrow(() -> objectMapper.writeValueAsString(eventPayload));

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId("evt-retry");
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("200");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(1); // will become 2
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unavailable"));
        when(kafkaTemplate.send(eq("order.created"), eq("200"), any(OrderCreatedEvent.class))).thenReturn(failedFuture);

        Instant beforeRun = Instant.now();
        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PENDING", saved.getStatus());
        assertEquals(2, saved.getAttempts());
        assertNotNull(saved.getLastError());
        assertTrue(saved.getLastError().contains("Kafka broker unavailable"));
        assertNull(saved.getPublishedAt());
        assertNotNull(saved.getNextAttemptAt());
        // For attempt 2, delay is 1L << 2 = 4 seconds
        assertTrue(saved.getNextAttemptAt().isAfter(beforeRun.plusSeconds(3)));
    }

    @Test
    @DisplayName("Reaching maximum attempts (5) transitions outbox event status to FAILED")
    void testMaxAttemptsTransitionsToFailed() {
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                "evt-max-fail",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-fail",
                "300",
                300L,
                3L,
                new BigDecimal("25.00")
        );

        String payloadJson = assertDoesNotThrow(() -> objectMapper.writeValueAsString(eventPayload));

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId("evt-max-fail");
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("300");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(4); // will become 5 >= MAX_ATTEMPTS
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Fatal broker timeout"));
        when(kafkaTemplate.send(eq("order.created"), eq("300"), any(OrderCreatedEvent.class))).thenReturn(failedFuture);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("FAILED", saved.getStatus());
        assertEquals(5, saved.getAttempts());
        assertNotNull(saved.getLastError());
        assertTrue(saved.getLastError().contains("Fatal broker timeout"));
        assertNull(saved.getPublishedAt());
    }

    @Test
    @DisplayName("Disabled publisher skips polling and publishing completely")
    void testDisabledPublisherDoesNotPoll() {
        ReflectionTestUtils.setField(publisher, "enabled", false);

        publisher.publishPendingEvents();

        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("Successful publish after previous retry attempts records PUBLISHED and clears error")
    void testSuccessfulRetryPublishMarksPublishedWithPriorAttempts() {
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                "evt-retry-success",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-retry-ok",
                "400",
                400L,
                1L,
                new BigDecimal("29.99")
        );

        String payloadJson = assertDoesNotThrow(() -> objectMapper.writeValueAsString(eventPayload));

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId("evt-retry-success");
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("400");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(2);
        outboxEvent.setLastError("Previous connection error");
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("400"), any(OrderCreatedEvent.class))).thenReturn(future);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PUBLISHED", saved.getStatus());
        assertNotNull(saved.getPublishedAt());
        assertNull(saved.getLastError(), "Error must be cleared on successful publish");
        assertEquals(2, saved.getAttempts(), "Attempts count remains unchanged upon success");
    }

    @Test
    @DisplayName("Sanitizes sensitive information (passwords, tokens) in outbox error message")
    void testSanitizesSensitiveInformationInLastError() {
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                "evt-sensitive-err",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-sec",
                "500",
                500L,
                1L,
                new BigDecimal("10.00")
        );

        String payloadJson = assertDoesNotThrow(() -> objectMapper.writeValueAsString(eventPayload));

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId("evt-sensitive-err");
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("500");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(0);
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Connection failed: password=supersecretpass;token=jwt_token_secret;user=order_app"));
        when(kafkaTemplate.send(eq("order.created"), eq("500"), any(OrderCreatedEvent.class))).thenReturn(failedFuture);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertNotNull(saved.getLastError());
        assertFalse(saved.getLastError().contains("supersecretpass"), "Password must be sanitized");
        assertFalse(saved.getLastError().contains("jwt_token_secret"), "Token must be sanitized");
        assertTrue(saved.getLastError().contains("password=***"));
        assertTrue(saved.getLastError().contains("token=***"));
    }
}
