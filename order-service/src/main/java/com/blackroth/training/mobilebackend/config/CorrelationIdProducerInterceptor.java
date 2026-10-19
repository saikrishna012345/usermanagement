package com.blackroth.training.mobilebackend.config;

import com.blackroth.training.mobilebackend.event.OrderCreatedEvent;
import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka Producer Interceptor that guarantees every published Kafka message
 * carries the X-Correlation-ID message header for distributed tracing.
 */
public class CorrelationIdProducerInterceptor implements ProducerInterceptor<String, OrderCreatedEvent> {

    @Override
    public ProducerRecord<String, OrderCreatedEvent> onSend(ProducerRecord<String, OrderCreatedEvent> record) {
        if (record == null) {
            return null;
        }

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
        return record;
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
