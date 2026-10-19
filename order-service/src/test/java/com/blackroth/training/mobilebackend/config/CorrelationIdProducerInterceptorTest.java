package com.blackroth.training.mobilebackend.config;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdProducerInterceptorTest {

    private CorrelationIdProducerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdProducerInterceptor();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Interceptor adds X-Correlation-ID header from MDC when present")
    void testAddsHeaderFromMdc() {
        String mdcCorrelationId = "corr-mdc-12345";
        MDC.put(CorrelationIdUtils.MDC_KEY, mdcCorrelationId);

        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-1", "ORDER_CREATED", "1.0", Instant.now(), null, "10", 10L, 1L, new BigDecimal("100.00")
        );
        ProducerRecord<String, OrderCreatedEvent> record =
                new ProducerRecord<>("order.created", "10", event);

        ProducerRecord<String, OrderCreatedEvent> intercepted = interceptor.onSend(record);

        Header header = intercepted.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
        assertNotNull(header);
        assertEquals(mdcCorrelationId, new String(header.value(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Interceptor adds X-Correlation-ID header from event payload when MDC is empty")
    void testAddsHeaderFromEventPayloadWhenMdcEmpty() {
        String payloadCorrelationId = "corr-payload-999";
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-2", "ORDER_CREATED", "1.0", Instant.now(), payloadCorrelationId, "20", 20L, 1L, new BigDecimal("50.00")
        );
        ProducerRecord<String, OrderCreatedEvent> record =
                new ProducerRecord<>("order.created", "20", event);

        ProducerRecord<String, OrderCreatedEvent> intercepted = interceptor.onSend(record);

        Header header = intercepted.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
        assertNotNull(header);
        assertEquals(payloadCorrelationId, new String(header.value(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Interceptor generates safe UUID fallback header when both MDC and event lack correlation ID")
    void testGeneratesFallbackHeaderWhenBothMissing() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-3", "ORDER_CREATED", "1.0", Instant.now(), null, "30", 30L, 1L, new BigDecimal("75.00")
        );
        ProducerRecord<String, OrderCreatedEvent> record =
                new ProducerRecord<>("order.created", "30", event);

        ProducerRecord<String, OrderCreatedEvent> intercepted = interceptor.onSend(record);

        Header header = intercepted.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
        assertNotNull(header);
        String headerValue = new String(header.value(), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> UUID.fromString(headerValue));
    }

    @Test
    @DisplayName("Interceptor preserves existing X-Correlation-ID header if already set on record")
    void testPreservesExistingHeader() {
        RecordHeaders headers = new RecordHeaders();
        String existingId = "corr-existing-header";
        headers.add(CorrelationIdUtils.CORRELATION_ID_HEADER, existingId.getBytes(StandardCharsets.UTF_8));

        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-4", "ORDER_CREATED", "1.0", Instant.now(), "corr-other", "40", 40L, 1L, new BigDecimal("80.00")
        );
        ProducerRecord<String, OrderCreatedEvent> record =
                new ProducerRecord<>("order.created", null, "40", event, headers);

        ProducerRecord<String, OrderCreatedEvent> intercepted = interceptor.onSend(record);

        Header header = intercepted.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
        assertNotNull(header);
        assertEquals(existingId, new String(header.value(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Null record returns null safely without throwing exception")
    void testNullRecordReturnsNull() {
        assertNull(interceptor.onSend(null));
    }
}
