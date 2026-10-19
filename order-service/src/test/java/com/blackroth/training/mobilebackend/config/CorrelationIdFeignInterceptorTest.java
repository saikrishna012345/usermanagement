package com.blackroth.training.mobilebackend.config;

import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFeignInterceptorTest {

    private CorrelationIdFeignInterceptor interceptorConfig;
    private RequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptorConfig = new CorrelationIdFeignInterceptor();
        interceptor = interceptorConfig.correlationIdRequestInterceptor();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Propagates X-Correlation-ID and traceparent from MDC to Feign RequestTemplate")
    void testPropagatesCorrelationIdAndTraceparentFromMdc() {
        String corrId = "corr-feign-12345";
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        MDC.put(CorrelationIdUtils.MDC_KEY, corrId);
        MDC.put(TraceContextUtils.MDC_TRACEPARENT, traceparent);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> corrHeaders = template.headers().get(CorrelationIdUtils.CORRELATION_ID_HEADER);
        assertNotNull(corrHeaders);
        assertTrue(corrHeaders.contains(corrId));

        Collection<String> traceHeaders = template.headers().get(TraceContextUtils.TRACEPARENT_HEADER);
        assertNotNull(traceHeaders);
        assertTrue(traceHeaders.contains(traceparent));
    }

    @Test
    @DisplayName("Constructs traceparent from MDC traceId and spanId when traceparent key is absent")
    void testConstructsTraceparentFromTraceIdAndSpanId() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanId = "00f067aa0ba902b7";

        MDC.put(TraceContextUtils.MDC_TRACE_ID, traceId);
        MDC.put(TraceContextUtils.MDC_SPAN_ID, spanId);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> traceHeaders = template.headers().get(TraceContextUtils.TRACEPARENT_HEADER);
        assertNotNull(traceHeaders);
        String header = traceHeaders.iterator().next();
        assertTrue(header.startsWith("00-" + traceId + "-" + spanId));
    }

    @Test
    @DisplayName("Generates safe W3C traceparent fallback when MDC contains no trace context")
    void testGeneratesSafeTraceparentFallbackWhenMdcEmpty() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> traceHeaders = template.headers().get(TraceContextUtils.TRACEPARENT_HEADER);
        assertNotNull(traceHeaders);
        String header = traceHeaders.iterator().next();
        assertTrue(TraceContextUtils.isValidTraceparent(header));
    }

    @Test
    @DisplayName("Preserves existing traceparent header on RequestTemplate if already set")
    void testPreservesExistingTraceparentOnTemplate() {
        String existingTraceparent = "00-11112222333344445555666677778888-9999aaaabbbbcccc-01";
        RequestTemplate template = new RequestTemplate();
        template.header(TraceContextUtils.TRACEPARENT_HEADER, existingTraceparent);

        interceptor.apply(template);

        Collection<String> traceHeaders = template.headers().get(TraceContextUtils.TRACEPARENT_HEADER);
        assertNotNull(traceHeaders);
        assertEquals(1, traceHeaders.size());
        assertTrue(traceHeaders.contains(existingTraceparent));
    }
}
