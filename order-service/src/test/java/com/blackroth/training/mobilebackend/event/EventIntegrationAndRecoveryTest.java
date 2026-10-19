package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.service.DltReplayService;
import com.blackroth.training.mobilebackend.service.OrderService;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.PaymentAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * End-to-End Event Integration, Deduplication, and Failure Recovery Tests
 * Covers:
 * 1. End-to-End Outbox publication and Consumer processing lifecycle.
 * 2. Duplicate event delivery, idempotency, and concurrent race-condition safety.
 * 3. Outbox transient failure recovery, exponential backoff, and failure threshold.
 * 4. Dead-Letter Topic (DLT) poison-pill rejection and DLT replay reprocessing.
 * 5. Saga consistency boundaries: reservation failure compensation and compensation pending.
 */
@ExtendWith(MockitoExtension.class)
class EventIntegrationAndRecoveryTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    @Mock
    private ProductClient productClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private PaymentAuthorizationService paymentAuthorizationService;

    private ObjectMapper objectMapper;
    private OutboxEventPublisher outboxPublisher;
    private OrderEventConsumer orderEventConsumer;
    private DltReplayService dltReplayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        outboxPublisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(outboxPublisher, "enabled", true);

        orderEventConsumer = new OrderEventConsumer(processedEventRepository);
        dltReplayService = new DltReplayService(kafkaTemplate, null);
    }

    @Test
    @DisplayName("End-to-End: Outbox publishes event with broker ACK, consumer processes and records deduplication")
    void testEndToEndEventFlowAndConsumerProcessing() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-e2e-101";
        Long orderId = 101L;

        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                eventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                correlationId,
                String.valueOf(orderId),
                orderId,
                1L,
                new BigDecimal("199.99")
        );

        String payloadJson = objectMapper.writeValueAsString(eventPayload);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId(String.valueOf(orderId));
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(0);
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq(String.valueOf(orderId)), any(OrderCreatedEvent.class)))
                .thenReturn(future);

        // Step 1: Outbox Publisher publishes event
        outboxPublisher.publishPendingEvents();

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent publishedOutbox = outboxCaptor.getValue();
        assertEquals("PUBLISHED", publishedOutbox.getStatus());
        assertNotNull(publishedOutbox.getPublishedAt());
        assertNull(publishedOutbox.getLastError());

        // Step 2: Consumer receives the published event
        when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);

        orderEventConsumer.consumeOrderCreated(eventPayload);

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).saveAndFlush(processedCaptor.capture());
        ProcessedEvent savedProcessed = processedCaptor.getValue();
        assertEquals(eventId, savedProcessed.getEventId());
        assertEquals("ORDER_CREATED", savedProcessed.getEventType());
    }

    @Test
    @DisplayName("Duplicate delivery: Consumer detects duplicate event and skips without duplicate database save")
    void testDuplicateDeliveryIsSkipped() {
        String duplicateEventId = "evt-dup-999";
        OrderCreatedEvent duplicateEvent = new OrderCreatedEvent(
                duplicateEventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-dup-999",
                505L,
                2L,
                new BigDecimal("49.99")
        );

        // First delivery: event already processed
        when(processedEventRepository.existsByEventId(duplicateEventId)).thenReturn(true);

        orderEventConsumer.consumeOrderCreated(duplicateEvent);

        verify(processedEventRepository).existsByEventId(duplicateEventId);
        verify(processedEventRepository, never()).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Race condition safety: Database unique constraint protects against concurrent duplicate delivery")
    void testConcurrentDuplicateDeliveryRaceConditionHandling() {
        String raceEventId = "evt-race-111";
        OrderCreatedEvent event = new OrderCreatedEvent(
                raceEventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-race",
                606L,
                3L,
                new BigDecimal("89.00")
        );

        // Simulate both threads passing existsByEventId check before either commits
        when(processedEventRepository.existsByEventId(raceEventId)).thenReturn(false);
        // Second concurrent save triggers unique constraint violation on uk_processed_event_id
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint uk_processed_event_id"));

        assertThrows(DataIntegrityViolationException.class, () -> orderEventConsumer.consumeOrderCreated(event));
    }

    @Test
    @DisplayName("Failure and recovery: Outbox publication fails with broker timeout, recovers on subsequent attempt")
    void testOutboxTransientFailureAndRecovery() throws Exception {
        String eventId = "evt-recover-303";
        OrderCreatedEvent payload = new OrderCreatedEvent(
                eventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-rec",
                707L,
                1L,
                new BigDecimal("120.00")
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("707");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(payloadJson);
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(0);
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        // Attempt 1: Kafka fails
        CompletableFuture<SendResult<String, OrderCreatedEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker timeout on partition 0"));
        when(kafkaTemplate.send(eq("order.created"), eq("707"), any(OrderCreatedEvent.class))).thenReturn(failedFuture);

        outboxPublisher.publishPendingEvents();

        assertEquals("PENDING", outboxEvent.getStatus());
        assertEquals(1, outboxEvent.getAttempts());
        assertTrue(outboxEvent.getLastError().contains("Broker timeout"));
        assertNull(outboxEvent.getPublishedAt());

        // Attempt 2: Kafka broker recovers
        CompletableFuture<SendResult<String, OrderCreatedEvent>> recoveredFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("707"), any(OrderCreatedEvent.class))).thenReturn(recoveredFuture);

        outboxPublisher.publishPendingEvents();

        assertEquals("PUBLISHED", outboxEvent.getStatus());
        assertNotNull(outboxEvent.getPublishedAt());
        assertNull(outboxEvent.getLastError());
    }

    @Test
    @DisplayName("Failure threshold: Outbox event transitions to FAILED upon reaching 5 max attempts")
    void testOutboxExhaustionTransitionsToFailed() throws Exception {
        String eventId = "evt-exhaust-404";
        OrderCreatedEvent payload = new OrderCreatedEvent(
                eventId,
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-ex",
                808L,
                1L,
                new BigDecimal("50.00")
        );

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateId("808");
        outboxEvent.setTopic("order.created");
        outboxEvent.setPayload(objectMapper.writeValueAsString(payload));
        outboxEvent.setStatus("PENDING");
        outboxEvent.setAttempts(4); // 4th attempt previously recorded; this next will be 5
        outboxEvent.setNextAttemptAt(Instant.now());

        when(outboxRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(outboxEvent));

        CompletableFuture<SendResult<String, OrderCreatedEvent>> fatalFuture = new CompletableFuture<>();
        fatalFuture.completeExceptionally(new RuntimeException("Permanent cluster error"));
        when(kafkaTemplate.send(eq("order.created"), eq("808"), any(OrderCreatedEvent.class))).thenReturn(fatalFuture);

        outboxPublisher.publishPendingEvents();

        assertEquals("FAILED", outboxEvent.getStatus());
        assertEquals(5, outboxEvent.getAttempts());
        assertTrue(outboxEvent.getLastError().contains("Permanent cluster error"));
    }

    @Test
    @DisplayName("Dead-Letter Routing: Malformed event triggers non-retryable validation error bypassing consumer store")
    void testMalformedEventBypassesProcessingForDlt() {
        OrderCreatedEvent poisonPill = new OrderCreatedEvent(
                "invalid-pill",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-poison",
                null, // invalid orderId <= 0 / null
                1L,
                new BigDecimal("20.00")
        );

        assertThrows(IllegalArgumentException.class, () -> orderEventConsumer.consumeOrderCreated(poisonPill));
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("DLT Replay: Reprocesses quarantined event from DLT back to order.created topic successfully")
    void testDltReplaySuccess() {
        OrderCreatedEvent dltEvent = new OrderCreatedEvent(
                "evt-dlt-replay",
                "ORDER_CREATED",
                "1.0",
                Instant.now(),
                "corr-dlt",
                909L,
                5L,
                new BigDecimal("250.00")
        );

        CompletableFuture<SendResult<String, OrderCreatedEvent>> replayFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("order.created"), eq("909"), eq(dltEvent))).thenReturn(replayFuture);

        boolean replaySuccess = dltReplayService.replayEvent(dltEvent);
        assertTrue(replaySuccess);
        verify(kafkaTemplate).send("order.created", "909", dltEvent);
    }

    @Test
    @DisplayName("Saga Consistency: Inventory reservation failure triggers compensation release and marks order CANCELLED")
    void testSagaCompensationOnReservationFailure() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, "pass"));

        OrderService orderService = new OrderService(
                orderRepository,
                productClient,
                outboxRepository,
                objectMapper,
                orderStateService,
                paymentAuthorizationService
        );

        ProductClient.ProductResponse product = new ProductClient.ProductResponse(
                10L, "Sample Product", "Desc", BigDecimal.TEN, 100, "Cat");
        ProductClient.ProductApiResponse productResponse = new ProductClient.ProductApiResponse(
                true, null, "OK", product, null, null);

        when(productClient.getProduct(10L)).thenReturn(productResponse);

        when(orderStateService.savePendingOrder(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 1001L);
            return o;
        });

        // Simulate reservation failure on Product Service
        ProductClient.ProductApiResponse reservationFailed = new ProductClient.ProductApiResponse(
                false, 400, "Insufficient stock", null, "OUT_OF_STOCK", null);
        when(productClient.reserveStock(eq(10L), eq(1001L), anyInt())).thenReturn(reservationFailed);

        // Compensation release succeeds
        ProductClient.ProductApiResponse releaseSuccess = new ProductClient.ProductApiResponse(
                true, 200, "Released", product, null, null);
        when(productClient.releaseReservation(eq(10L), eq(1001L))).thenReturn(releaseSuccess);

        OrderItemRequest itemRequest1 = new OrderItemRequest();
        itemRequest1.setProductId(10L);
        itemRequest1.setQuantity(1);

        OrderRequest request = new OrderRequest();
        request.setUserId(5L);
        request.setItems(List.of(itemRequest1));

        assertThrows(BusinessException.class, () -> orderService.createOrder(request));

        // Verifies order is marked CANCELLED and compensation release was invoked
        verify(productClient).releaseReservation(10L, 1001L);
        verify(orderStateService).markCancelled(1001L);
        verify(orderStateService, never()).markConfirmed(anyLong());
        verify(orderStateService, never()).markCompensationPending(anyLong());

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Saga Consistency: Downstream compensation failure marks order as COMPENSATION_PENDING")
    void testSagaCompensationPendingWhenReleaseFails() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, "pass"));

        OrderService orderService = new OrderService(
                orderRepository,
                productClient,
                outboxRepository,
                objectMapper,
                orderStateService,
                paymentAuthorizationService
        );

        ProductClient.ProductResponse product = new ProductClient.ProductResponse(
                20L, "Gadget", "Desc", BigDecimal.valueOf(50), 10, "Cat");
        ProductClient.ProductApiResponse productResponse = new ProductClient.ProductApiResponse(
                true, null, "OK", product, null, null);

        when(productClient.getProduct(20L)).thenReturn(productResponse);

        when(orderStateService.savePendingOrder(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 2002L);
            return o;
        });

        // Reservation fails
        when(productClient.reserveStock(eq(20L), eq(2002L), anyInt()))
                .thenThrow(new RuntimeException("Product service network drop"));

        // Compensation release also fails (e.g. downstream service unreachable)
        when(productClient.releaseReservation(eq(20L), eq(2002L)))
                .thenThrow(new RuntimeException("Product service completely unreachable"));

        OrderItemRequest itemRequest2 = new OrderItemRequest();
        itemRequest2.setProductId(20L);
        itemRequest2.setQuantity(1);

        OrderRequest request = new OrderRequest();
        request.setUserId(5L);
        request.setItems(List.of(itemRequest2));

        assertThrows(BusinessException.class, () -> orderService.createOrder(request));

        // Verifies order is marked COMPENSATION_PENDING because compensation release failed
        verify(orderStateService).markCompensationPending(2002L);
        verify(orderStateService, never()).markCancelled(anyLong());
        verify(orderStateService, never()).markConfirmed(anyLong());

        SecurityContextHolder.clearContext();
    }
}
