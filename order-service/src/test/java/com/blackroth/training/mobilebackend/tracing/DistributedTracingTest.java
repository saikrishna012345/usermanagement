package com.blackroth.training.mobilebackend.tracing;

import com.blackroth.training.mobilebackend.config.CorrelationIdFeignInterceptor;
import com.blackroth.training.mobilebackend.config.CorrelationIdProducerInterceptor;
import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.event.OrderEventConsumer;
import com.blackroth.training.mobilebackend.filter.CorrelationIdFilter;
import com.blackroth.training.mobilebackend.filter.TraceContextFilter;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedTracingTest {

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpServletResponse httpResponse;

    @Mock
    private FilterChain filterChain;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private TraceContextFilter traceContextFilter;
    private CorrelationIdFilter correlationIdFilter;
    private RequestInterceptor feignInterceptor;
    private CorrelationIdProducerInterceptor producerInterceptor;
    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        traceContextFilter = new TraceContextFilter();
        correlationIdFilter = new CorrelationIdFilter();
        feignInterceptor = new CorrelationIdFeignInterceptor().correlationIdRequestInterceptor();
        producerInterceptor = new CorrelationIdProducerInterceptor();
        orderEventConsumer = new OrderEventConsumer(processedEventRepository);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("End-to-end trace propagation: HTTP -> Feign -> Kafka Producer -> Kafka Consumer")
    void testEndToEndTracePropagationAcrossBoundaries() throws Exception {
        String originalTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String originalCorrelationId = "corr-e2e-trace-12345";

        // Step 1: Incoming HTTP Request
        when(httpRequest.getHeader(TraceContextUtils.TRACEPARENT_HEADER)).thenReturn(originalTraceparent);
        when(httpRequest.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER)).thenReturn(originalCorrelationId);

        AtomicReference<String> mdcTraceInChain = new AtomicReference<>();
        AtomicReference<String> mdcCorrInChain = new AtomicReference<>();

        doAnswer(inv -> {
            mdcTraceInChain.set(MDC.get(TraceContextUtils.MDC_TRACEPARENT));
            mdcCorrInChain.set(MDC.get(CorrelationIdUtils.MDC_KEY));

            // Step 2: OpenFeign Downstream Call to Product Service during HTTP request execution
            RequestTemplate feignTemplate = new RequestTemplate();
            feignInterceptor.apply(feignTemplate);

            Collection<String> feignTraceHeaders = feignTemplate.headers().get(TraceContextUtils.TRACEPARENT_HEADER);
            assertNotNull(feignTraceHeaders);
            assertEquals(originalTraceparent, feignTraceHeaders.iterator().next());

            Collection<String> feignCorrHeaders = feignTemplate.headers().get(CorrelationIdUtils.CORRELATION_ID_HEADER);
            assertNotNull(feignCorrHeaders);
            assertEquals(originalCorrelationId, feignCorrHeaders.iterator().next());

            // Step 3: Kafka Producer publishes order.created event
            OrderCreatedEvent event = new OrderCreatedEvent(
                    "evt-e2e-1", "ORDER_CREATED", "1.0", Instant.now(),
                    originalCorrelationId, "500", 500L, 1L, new BigDecimal("299.99")
            );
            ProducerRecord<String, OrderCreatedEvent> record =
                    new ProducerRecord<>("order.created", "500", event);
            ProducerRecord<String, OrderCreatedEvent> interceptedRecord = producerInterceptor.onSend(record);

            Header kafkaTraceHeader = interceptedRecord.headers().lastHeader(TraceContextUtils.TRACEPARENT_HEADER);
            assertNotNull(kafkaTraceHeader);
            assertEquals(originalTraceparent, new String(kafkaTraceHeader.value(), StandardCharsets.UTF_8));

            Header kafkaCorrHeader = interceptedRecord.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
            assertNotNull(kafkaCorrHeader);
            assertEquals(originalCorrelationId, new String(kafkaCorrHeader.value(), StandardCharsets.UTF_8));

            return null;
        }).when(filterChain).doFilter(httpRequest, httpResponse);

        // Execute CorrelationIdFilter inside TraceContextFilter chain
        traceContextFilter.doFilter(httpRequest, httpResponse, (req, res) ->
                correlationIdFilter.doFilter(req, res, filterChain)
        );

        assertEquals(originalTraceparent, mdcTraceInChain.get());
        assertEquals(originalCorrelationId, mdcCorrInChain.get());
        verify(httpResponse).setHeader(TraceContextUtils.TRACEPARENT_HEADER, originalTraceparent);
        verify(httpResponse).setHeader(CorrelationIdUtils.CORRELATION_ID_HEADER, originalCorrelationId);

        // Step 4: Kafka Consumer receives the record with propagated headers
        when(processedEventRepository.existsByEventId("evt-e2e-1")).thenReturn(false);

        OrderCreatedEvent receivedEvent = new OrderCreatedEvent(
                "evt-e2e-1", "ORDER_CREATED", "1.0", Instant.now(),
                originalCorrelationId, "500", 500L, 1L, new BigDecimal("299.99")
        );

        assertDoesNotThrow(() -> orderEventConsumer.consumeOrderCreated(
                receivedEvent, "order.created", 0, 100L, originalCorrelationId, originalTraceparent
        ));

        verify(processedEventRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Trace ID and Correlation ID are distinct and non-interchangeable")
    void testTraceIdAndCorrelationIdAreDistinct() {
        String traceparent = TraceContextUtils.generateTraceparent();
        String traceId = TraceContextUtils.extractTraceId(traceparent);
        String correlationId = CorrelationIdUtils.resolveCorrelationId("my-business-correlation-id");

        assertNotEquals(traceId, correlationId);
        assertTrue(traceId.matches("^[0-9a-f]{32}$"));
        assertEquals("my-business-correlation-id", correlationId);
    }

    @Test
    @DisplayName("Privacy: sensitive data patterns are not permitted in trace context attributes")
    void testPrivacyAndNoSensitiveDataInTraceAttributes() {
        String sensitiveJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLogThisToken";
        String password = "superSecretPassword123!";

        assertFalse(TraceContextUtils.isValidTraceparent(sensitiveJwt));
        assertFalse(TraceContextUtils.isValidTraceparent(password));
    }
}
