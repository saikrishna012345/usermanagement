package com.blackroth.training.mobilebackend.config;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka Producer Interceptor that guarantees every published Kafka message
 * carries both the X-Correlation-ID message header and the W3C traceparent header
 * for distributed tracing across Kafka messaging boundaries.
 */
public class CorrelationIdProducerInterceptor implements ProducerInterceptor<String, OrderCreatedEvent> {

    @Override
    public ProducerRecord<String, OrderCreatedEvent> onSend(ProducerRecord<String, OrderCreatedEvent> record) {
        if (record == null) {
            return null;
        }

        // 1. Correlation ID Header
        String correlationId = MDC.get(CorrelationIdUtils.MDC_KEY);
        if ((correlationId == null || correlationId.isBlank()) && record.value() != null) {
            correlationId = record.value().correlationId();
        }
        correlationId = CorrelationIdUtils.resolveCorrelationId(correlationId);

        if (record.headers().lastHeader(CorrelationIdUtils.CORRELATION_ID_HEADER) == null) {
            record.headers().add(
                    CorrelationIdUtils.CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8)
            );
        }

        // 2. W3C traceparent Header
        Header existingTraceHeader = record.headers().lastHeader(TraceContextUtils.TRACEPARENT_HEADER);
        if (existingTraceHeader == null) {
            String traceparent = resolveTraceparentForRecord();
            record.headers().add(
                    TraceContextUtils.TRACEPARENT_HEADER,
                    traceparent.getBytes(StandardCharsets.UTF_8)
            );
        } else {
            String existingVal = new String(existingTraceHeader.value(), StandardCharsets.UTF_8);
            if (!TraceContextUtils.isValidTraceparent(existingVal)) {
                // If malformed or all zeros, replace with safe valid traceparent
                record.headers().remove(TraceContextUtils.TRACEPARENT_HEADER);
                String safeTraceparent = TraceContextUtils.generateTraceparent();
                record.headers().add(
                        TraceContextUtils.TRACEPARENT_HEADER,
                        safeTraceparent.getBytes(StandardCharsets.UTF_8)
                );
            }
        }

        return record;
    }

    private String resolveTraceparentForRecord() {
        String traceparent = MDC.get(TraceContextUtils.MDC_TRACEPARENT);
        if (traceparent != null && TraceContextUtils.isValidTraceparent(traceparent)) {
            return traceparent;
        }

        String traceId = MDC.get(TraceContextUtils.MDC_TRACE_ID);
        String spanId = MDC.get(TraceContextUtils.MDC_SPAN_ID);
        if (traceId != null && spanId != null) {
            traceparent = TraceContextUtils.buildTraceparent(traceId, spanId, true);
            if (TraceContextUtils.isValidTraceparent(traceparent)) {
                return traceparent;
            }
        }

        return TraceContextUtils.generateTraceparent();
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
