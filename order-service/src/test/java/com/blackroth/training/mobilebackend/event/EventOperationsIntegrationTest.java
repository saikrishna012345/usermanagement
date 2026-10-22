package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.metrics.EventMetrics;
import com.blackroth.training.mobilebackend.model.Order;
import com.blackroth.training.mobilebackend.model.OrderItem;
import com.blackroth.training.mobilebackend.model.OrderStatus;
import com.blackroth.training.mobilebackend.model.OutboxEvent;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.service.DltReplayService;
import com.blackroth.training.mobilebackend.service.OrderStateService;
import com.blackroth.training.mobilebackend.service.SagaCompensationRecoveryService;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventOperationsIntegrationTest {

    private OrderRepository orderRepository;
    private OutboxEventRepository outboxEventRepository;
    private ProcessedEventRepository processedEventRepository;
    private ProductClient productClient;
    private KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private EventMetrics eventMetrics;

    private OrderStateService orderStateService;
    private OutboxEventPublisher outboxPublisher;
    private OrderEventConsumer orderConsumer;
    private DltReplayService dltReplayService;
    private SagaCompensationRecoveryService sagaRecoveryService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        productClient = mock(ProductClient.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        meterRegistry = new SimpleMeterRegistry();
        eventMetrics = new EventMetrics(meterRegistry, outboxEventRepository, orderRepository);
        eventMetrics.registerGauges();

        orderStateService = new OrderStateService(orderRepository, outboxEventRepository, objectMapper);
        outboxPublisher = new OutboxEventPublisher(outboxEventRepository, kafkaTemplate, objectMapper, null, eventMetrics);
        org.springframework.test.util.ReflectionTestUtils.setField(outboxPublisher, "enabled", true);
        orderConsumer = new OrderEventConsumer(processedEventRepository, null, eventMetrics);
        dltReplayService = new DltReplayService(kafkaTemplate, null, eventMetrics);
        sagaRecoveryService = new SagaCompensationRecoveryService(orderRepository, productClient, orderStateService, eventMetrics);
        org.springframework.test.util.ReflectionTestUtils.setField(sagaRecoveryService, "enabled", true);
    }

    @Test
    @DisplayName("Practical Trace: Complete transaction traces correlation ID across Order, Outbox, and Consumer")
    void testCompleteTransactionTraceAndMetrics() throws Exception {
        String correlationId = "corr-test-trace-12345";
        MDC.put(CorrelationIdUtils.MDC_KEY, correlationId);

        try {
            // 1. Order Creation
            Order order = new Order(101L, OrderStatus.PENDING, BigDecimal.valueOf(199.99));
            order.setId(1001L);

            when(orderRepository.saveAndFlush(any(Order.class))).thenReturn(order);
            when(orderRepository.findById(1001L)).thenReturn(Optional.of(order));

            Order confirmed = orderStateService.markConfirmed(1001L);
            assertEquals(OrderStatus.CONFIRMED, confirmed.getStatus());

            // 2. Outbox Event Saved with preserved correlation ID
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(outboxCaptor.capture());
            OutboxEvent savedOutbox = outboxCaptor.getValue();

            assertNotNull(savedOutbox);
            assertEquals("PENDING", savedOutbox.getStatus());
            assertEquals("order.created", savedOutbox.getTopic());
            assertEquals("1001", savedOutbox.getAggregateId());

            OrderCreatedEvent eventPayload = objectMapper.readValue(savedOutbox.getPayload(), OrderCreatedEvent.class);
            assertEquals(correlationId, eventPayload.correlationId());
            assertEquals(1001L, eventPayload.orderId());

            // 3. Outbox Publisher forwards to Kafka and records metrics
            SendResult<String, OrderCreatedEvent> sendResult = mock(SendResult.class);
            CompletableFuture<SendResult<String, OrderCreatedEvent>> future = CompletableFuture.completedFuture(sendResult);
            when(kafkaTemplate.send(anyString(), anyString(), any(OrderCreatedEvent.class))).thenReturn(future);
            when(outboxEventRepository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                    .thenReturn(List.of(savedOutbox));

            outboxPublisher.publishPendingEvents();

            assertEquals("PUBLISHED", savedOutbox.getStatus());
            assertEquals(1.0, meterRegistry.find("events.outbox.published")
                    .tag("outcome", "published").counter().count());

            // 4. Consumer receives and processes event, recording metrics and deduplication state
            when(processedEventRepository.existsByEventId(eventPayload.eventId())).thenReturn(false);

            orderConsumer.consumeOrderCreated(eventPayload, "order.created", 0, 100L, correlationId);

            verify(processedEventRepository).saveAndFlush(any());
            assertEquals(1.0, meterRegistry.find("events.consumer.processed")
                    .tag("outcome", "success").counter().count());

        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("Duplicate Event Replay: Consumer skips duplicate event and records duplicate metric without side effects")
    void testDuplicateEventReplayMetrics() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dup-101",
                "OrderCreated",
                1002L,
                101L,
                BigDecimal.valueOf(50.00),
                Instant.now()
        );

        when(processedEventRepository.existsByEventId("evt-dup-101")).thenReturn(true);

        orderConsumer.consumeOrderCreated(event, "order.created", 0, 101L, "corr-dup-101");

        verify(processedEventRepository, never()).saveAndFlush(any());
        assertEquals(1.0, meterRegistry.find("events.consumer.processed")
                .tag("outcome", "duplicate").counter().count());
    }

    @Test
    @DisplayName("Injected Failure: Poison message triggers validation error, DLT metrics recording, and subsequent DLT replay")
    void testPoisonMessageHandlingAndDltReplay() {
        // 1. Poison message with invalid null orderId
        OrderCreatedEvent poisonEvent = new OrderCreatedEvent(
                "evt-poison-1",
                "OrderCreated",
                "1.0",
                Instant.now(),
                "corr-poison-1",
                null, // invalid null orderId
                101L,
                BigDecimal.valueOf(25.00)
        );

        assertThrows(IllegalArgumentException.class, () ->
                orderConsumer.consumeOrderCreated(poisonEvent, "order.created", 0, 102L, "corr-poison-1"));

        assertEquals(1.0, meterRegistry.find("events.consumer.dlt")
                .tag("reason", "validation_error").counter().count());
        assertEquals(1.0, meterRegistry.find("events.consumer.processed")
                .tag("outcome", "dlt_routed").counter().count());

        // 2. Corrected message replay via DltReplayService
        OrderCreatedEvent correctedEvent = new OrderCreatedEvent(
                "evt-poison-1",
                "OrderCreated",
                "1.0",
                Instant.now(),
                "corr-poison-1",
                1003L, // corrected
                101L,
                BigDecimal.valueOf(25.00)
        );

        SendResult<String, OrderCreatedEvent> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(anyString(), anyString(), any(OrderCreatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        boolean replaySuccess = dltReplayService.replayEvent(correctedEvent);
        assertTrue(replaySuccess);
        assertEquals(1.0, meterRegistry.find("events.dlt.replay")
                .tag("outcome", "success").counter().count());
    }

    @Test
    @DisplayName("Injected Failure: Saga compensation failure transitions order to COMPENSATION_PENDING and recovery succeeds")
    void testSagaCompensationFailureAndRecovery() {
        Order order = new Order(101L, OrderStatus.COMPENSATION_PENDING, BigDecimal.valueOf(99.00));
        order.setId(2001L);
        order.addOrderItem(new OrderItem(501L, "Test Product", 2, BigDecimal.valueOf(49.50)));

        when(orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(2001L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenReturn(order);

        // Product service successfully releases reservation upon recovery
        ProductClient.ProductResponse prodResp = new ProductClient.ProductResponse(
                501L, "Test Product", "Description", BigDecimal.valueOf(49.50), 100, "Category"
        );
        ProductClient.ProductApiResponse apiResp = new ProductClient.ProductApiResponse(
                true, 200, "Reservation released", prodResp, null, Instant.now().toString()
        );
        when(productClient.releaseReservation(eq(501L), eq(2001L))).thenReturn(apiResp);

        int recovered = sagaRecoveryService.recoverPendingCompensations();
        assertEquals(1, recovered);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        assertEquals(1.0, meterRegistry.find("saga.compensation.total")
                .tag("outcome", "recovered").counter().count());
    }
}
