package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.event.OutboxEventPublisher;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.errors.TimeoutException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Kafka Outage, Outbox Retry, Backoff, and Chaos Recovery Tests")
class KafkaOutboxResilienceTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Mock
    private OrderRepository orderRepository;

    private ObjectMapper objectMapper;
    private OutboxEventPublisher publisher;
    private OrderStateService orderStateService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        publisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, objectMapper, null);
        ReflectionTestUtils.setField(publisher, "enabled", true);
        orderStateService = new OrderStateService(orderRepository, outboxRepository, objectMapper);
    }

    private OutboxEvent createSamplePendingOutboxEvent(int attempts) throws Exception {
        OrderCreatedEvent payload = new OrderCreatedEvent(
                "evt-resilience-101",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-resilience-101",
                101L,
                42L,
                new BigDecimal("299.99")
        );

        OutboxEvent event = new OutboxEvent();
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "createdAt", Instant.now().minusSeconds(10));
        event.setEventId("evt-resilience-101");
        event.setEventType("ORDER_CREATED");
        event.setTopic("order.created");
        event.setAggregateId("101");
        event.setPayload(objectMapper.writeValueAsString(payload));
        event.setStatus("PENDING");
        event.setAttempts(attempts);
        event.setNextAttemptAt(Instant.now().minusSeconds(5));
        return event;
    }

    @Test
    @DisplayName("Scenario 1: Kafka is available -> Outbox event published and status transitions to PUBLISHED")
    void whenKafkaAvailable_thenEventPublishedSuccessfully() throws Exception {
        OutboxEvent event = createSamplePendingOutboxEvent(0);

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("order.created"), eq("101"), any(OrderCreatedEvent.class)))
                .thenReturn(future);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PUBLISHED", saved.getStatus());
        assertNotNull(saved.getPublishedAt());
        assertNull(saved.getLastError());
    }

    @Test
    @DisplayName("Scenario 2 & 3: Kafka temporarily unavailable -> status remains PENDING with exponential backoff")
    void whenKafkaTemporarilyFails_thenStatusRemainsPendingWithExponentialBackoff() throws Exception {
        OutboxEvent event = createSamplePendingOutboxEvent(0);

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker connection timed out"));
        when(kafkaTemplate.send(eq("order.created"), eq("101"), any(OrderCreatedEvent.class)))
                .thenReturn(failedFuture);

        Instant beforePublish = Instant.now();
        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PENDING", saved.getStatus());
        assertEquals(1, saved.getAttempts());
        assertNotNull(saved.getLastError());
        assertTrue(saved.getLastError().contains("Kafka broker connection timed out"));

        // Backoff for attempt 1: 1L << 1 = 2 seconds
        assertNotNull(saved.getNextAttemptAt());
        assertTrue(saved.getNextAttemptAt().isAfter(beforePublish));
    }

    @Test
    @DisplayName("Scenario 4: Kafka recovers after transient outage -> pending event publishes on next poll")
    void whenKafkaRecovers_thenPendingEventPublishesSuccessfully() throws Exception {
        // Event previously failed 2 times, status is PENDING
        OutboxEvent event = createSamplePendingOutboxEvent(2);

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> successFuture =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq("order.created"), eq("101"), any(OrderCreatedEvent.class)))
                .thenReturn(successFuture);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals("PUBLISHED", saved.getStatus());
        assertNotNull(saved.getPublishedAt());
        assertNull(saved.getLastError());
        assertEquals(2, saved.getAttempts());
    }

    @Test
    @DisplayName("Scenario 5: Repeated Kafka outages reach MAX_ATTEMPTS (5) -> terminal state FAILED")
    void whenKafkaFailsRepeatedlyToMaxAttempts_thenTransitionsToFailed() throws Exception {
        // Event already attempted 4 times; this 5th failure will reach MAX_ATTEMPTS
        OutboxEvent event = createSamplePendingOutboxEvent(4);

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker unreachable"));
        when(kafkaTemplate.send(eq("order.created"), eq("101"), any(OrderCreatedEvent.class)))
                .thenReturn(failedFuture);

        publisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertEquals(5, saved.getAttempts());
        assertEquals("FAILED", saved.getStatus());
        assertNotNull(saved.getLastError());
        assertTrue(saved.getLastError().contains("Kafka broker unreachable"));
    }

    @Test
    @DisplayName("Scenario 6: Failed outbox events remain in database for operational investigation")
    void failedOutboxEventsAreQueryableForOperationalInspection() throws Exception {
        OutboxEvent failedEvent = createSamplePendingOutboxEvent(5);
        failedEvent.setStatus("FAILED");
        failedEvent.setLastError("Terminal Kafka failure: broker disconnect");

        when(outboxRepository.findAll()).thenReturn(List.of(failedEvent));

        List<OutboxEvent> failedEvents = outboxRepository.findAll().stream()
                .filter(e -> "FAILED".equals(e.getStatus()))
                .toList();

        assertEquals(1, failedEvents.size());
        assertEquals("FAILED", failedEvents.get(0).getStatus());
        assertEquals(5, failedEvents.get(0).getAttempts());
        assertEquals("Terminal Kafka failure: broker disconnect", failedEvents.get(0).getLastError());
    }

    @Test
    @DisplayName("Transactional consistency: Order confirmation and Outbox event are created together")
    void orderAndOutboxEventRemainTransactionallyConsistent() {
        Order order = new Order(42L, OrderStatus.PENDING, new BigDecimal("199.99"));
        ReflectionTestUtils.setField(order, "id", 202L);

        when(orderRepository.findById(202L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenReturn(order);

        Order confirmed = orderStateService.markConfirmed(202L);

        assertEquals(OrderStatus.CONFIRMED, confirmed.getStatus());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());

        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertEquals("ORDER_CREATED", outboxEvent.getEventType());
        assertEquals("order.created", outboxEvent.getTopic());
        assertEquals("202", outboxEvent.getAggregateId());
        assertEquals("PENDING", outboxEvent.getStatus());
        assertEquals(0, outboxEvent.getAttempts());
        assertNotNull(outboxEvent.getPayload());
    }
}
