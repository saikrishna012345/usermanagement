package com.blackroth.training.mobilebackend.config;

import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CorrelationIdFeignInterceptor {

    @Bean
    public RequestInterceptor correlationIdRequestInterceptor() {

        return requestTemplate -> {

            // 1. Propagate X-Correlation-ID
            String correlationId = MDC.get(CorrelationIdUtils.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                requestTemplate.header(
                        CorrelationIdUtils.CORRELATION_ID_HEADER,
                        correlationId
                );
            }

            // 2. Propagate W3C traceparent
            if (!requestTemplate.headers().containsKey(TraceContextUtils.TRACEPARENT_HEADER)) {
                String traceparent = MDC.get(TraceContextUtils.MDC_TRACEPARENT);
                if (traceparent == null || !TraceContextUtils.isValidTraceparent(traceparent)) {
                    String traceId = MDC.get(TraceContextUtils.MDC_TRACE_ID);
                    String spanId = MDC.get(TraceContextUtils.MDC_SPAN_ID);
                    if (traceId != null && spanId != null) {
                        traceparent = TraceContextUtils.buildTraceparent(traceId, spanId, true);
                    }
                }
                if (traceparent == null || !TraceContextUtils.isValidTraceparent(traceparent)) {
                    traceparent = TraceContextUtils.generateTraceparent();
                }
                requestTemplate.header(TraceContextUtils.TRACEPARENT_HEADER, traceparent);
            }
        };
    }
}