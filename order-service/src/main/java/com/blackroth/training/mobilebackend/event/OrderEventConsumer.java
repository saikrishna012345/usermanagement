package com.blackroth.training.mobilebackend.event;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import com.blackroth.training.mobilebackend.repository.ProcessedEventRepository;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ProcessedEventRepository processedEventRepository;
    private final Tracer tracer;

    public OrderEventConsumer(
            ProcessedEventRepository processedEventRepository) {
        this(processedEventRepository, null);
    }

    @Autowired
    public OrderEventConsumer(
            ProcessedEventRepository processedEventRepository,
            @Autowired(required = false) Tracer tracer) {
        this.processedEventRepository = processedEventRepository;
        this.tracer = tracer;
    }

    @KafkaListener(
            topics = "order.created",
            groupId = "order-service"
    )
    @Transactional
    public void consumeOrderCreated(
            @Payload(required = false) OrderCreatedEvent event,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
            @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
            @Header(name = CorrelationIdUtils.CORRELATION_ID_HEADER, required = false) String headerCorrelationId,
            @Header(name = TraceContextUtils.TRACEPARENT_HEADER, required = false) String headerTraceparent
    ) {
        processOrderCreated(event, topic != null ? topic : "order.created", partition, offset, headerCorrelationId, headerTraceparent);
    }

    public void consumeOrderCreated(
            OrderCreatedEvent event,
            String topic,
            Integer partition,
            Long offset,
            String headerCorrelationId
    ) {
        processOrderCreated(event, topic != null ? topic : "order.created", partition, offset, headerCorrelationId, null);
    }

    public void consumeOrderCreated(OrderCreatedEvent event) {
        processOrderCreated(event, "order.created", null, null, null, null);
    }

    private void processOrderCreated(
            OrderCreatedEvent event,
            String topic,
            Integer partition,
            Long offset,
            String headerCorrelationId,
            String headerTraceparent
    ) {
        long startTime = System.currentTimeMillis();

        // 1. Resolve Correlation ID
        String rawCorrelation = headerCorrelationId != null && !headerCorrelationId.isBlank()
                ? headerCorrelationId
                : (event != null ? event.correlationId() : null);
        String correlationId = CorrelationIdUtils.resolveCorrelationId(rawCorrelation);

        // 2. Resolve W3C Trace Context (safe against null, malformed, or all-zero headers)
        String traceparent = TraceContextUtils.resolveTraceparent(headerTraceparent);
        String traceId = TraceContextUtils.extractTraceId(traceparent);
        String spanId = TraceContextUtils.extractSpanId(traceparent);

        Span consumerSpan = null;
        Tracer.SpanInScope spanInScope = null;

        if (tracer != null) {
            try {
                consumerSpan = tracer.nextSpan().name("order.created receive");
                consumerSpan.tag("messaging.system", "kafka");
                consumerSpan.tag("messaging.destination.name", topic != null ? topic : "order.created");
                consumerSpan.tag("messaging.operation", "receive");
                consumerSpan.tag("messaging.kafka.consumer.group", "order-service");
                if (event != null && event.eventType() != null) {
                    consumerSpan.tag("event.type", event.eventType());
                }
                if (event != null && event.orderId() != null) {
                    consumerSpan.tag("order.id", String.valueOf(event.orderId()));
                }
                consumerSpan.start();
                spanInScope = tracer.withSpan(consumerSpan);
            } catch (Exception e) {
                log.debug("Unable to initialize tracing span for Kafka consumer: {}", e.getMessage());
            }
        }

        try {
            MDC.put(CorrelationIdUtils.MDC_KEY, correlationId);
            MDC.put("service", "order-service");
            if (traceId != null) MDC.put(TraceContextUtils.MDC_TRACE_ID, traceId);
            if (spanId != null) MDC.put(TraceContextUtils.MDC_SPAN_ID, spanId);
            MDC.put(TraceContextUtils.MDC_TRACEPARENT, traceparent);

            if (topic != null) MDC.put("topic", topic);
            if (partition != null) MDC.put("partition", String.valueOf(partition));
            if (offset != null) MDC.put("offset", String.valueOf(offset));

            if (event == null) {
                log.error("Consumer event rejected: payload=null, topic={}, partition={}, offset={}, outcome=DLT_ROUTED",
                        topic, partition, offset);
                throw new IllegalArgumentException("Order event must not be null");
            }

            if (event.eventId() != null) MDC.put("eventId", event.eventId());
            if (event.eventType() != null) MDC.put("eventType", event.eventType());
            if (event.orderId() != null) MDC.put("orderId", String.valueOf(event.orderId()));

            try {
                event.validate();
            } catch (IllegalArgumentException ex) {
                log.error("Consumer event validation failed: eventId={}, orderId={}, error={}, topic={}, partition={}, offset={}, outcome=DLT_ROUTED",
                        event.eventId(), event.orderId(), ex.getMessage(), topic, partition, offset);
                if (consumerSpan != null) {
                    consumerSpan.error(ex);
                }
                throw ex;
            }

            log.info("Consumer event received: eventId={}, eventType={}, orderId={}, topic={}, partition={}, offset={}, traceId={}, outcome=RECEIVED",
                    event.eventId(), event.eventType(), event.orderId(), topic, partition, offset, traceId);

            if (processedEventRepository.existsByEventId(event.eventId())) {
                long durationMs = System.currentTimeMillis() - startTime;
                log.warn("Duplicate event skipped: eventId={}, eventType={}, orderId={}, topic={}, partition={}, offset={}, durationMs={}, outcome=SKIPPED_DUPLICATE",
                        event.eventId(), event.eventType(), event.orderId(), topic, partition, offset, durationMs);
                return;
            }

            processedEventRepository.saveAndFlush(
                    new ProcessedEvent(
                            event.eventId(),
                            event.eventType()
                    )
            );

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Consumer event processed successfully: eventId={}, eventType={}, orderId={}, topic={}, partition={}, offset={}, durationMs={}, traceId={}, outcome=PROCESSED",
                    event.eventId(), event.eventType(), event.orderId(), topic, partition, offset, durationMs, traceId);

        } catch (RuntimeException ex) {
            if (consumerSpan != null) {
                consumerSpan.error(ex);
            }
            throw ex;
        } finally {
            if (spanInScope != null) {
                spanInScope.close();
            }
            if (consumerSpan != null) {
                consumerSpan.end();
            }

            MDC.remove(CorrelationIdUtils.MDC_KEY);
            MDC.remove("service");
            MDC.remove(TraceContextUtils.MDC_TRACE_ID);
            MDC.remove(TraceContextUtils.MDC_SPAN_ID);
            MDC.remove(TraceContextUtils.MDC_TRACEPARENT);
            MDC.remove("topic");
            MDC.remove("partition");
            MDC.remove("offset");
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("orderId");
        }
    }
}