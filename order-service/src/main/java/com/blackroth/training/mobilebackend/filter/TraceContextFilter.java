package com.blackroth.training.mobilebackend.filter;

import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter for W3C distributed trace context propagation on HTTP requests in order-service.
 * Extracts incoming traceparent header if valid, generates a safe compliant fallback
 * if missing or malformed, populates MDC, and mirrors the traceparent in response headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String rawTraceparent = request.getHeader(TraceContextUtils.TRACEPARENT_HEADER);
        String traceparent = TraceContextUtils.resolveTraceparent(rawTraceparent);
        String traceId = TraceContextUtils.extractTraceId(traceparent);
        String spanId = TraceContextUtils.extractSpanId(traceparent);

        try {
            if (traceId != null) {
                MDC.put(TraceContextUtils.MDC_TRACE_ID, traceId);
            }
            if (spanId != null) {
                MDC.put(TraceContextUtils.MDC_SPAN_ID, spanId);
            }
            MDC.put(TraceContextUtils.MDC_TRACEPARENT, traceparent);

            response.setHeader(TraceContextUtils.TRACEPARENT_HEADER, traceparent);

            filterChain.doFilter(request, response);

        } finally {
            MDC.remove(TraceContextUtils.MDC_TRACE_ID);
            MDC.remove(TraceContextUtils.MDC_SPAN_ID);
            MDC.remove(TraceContextUtils.MDC_TRACEPARENT);
        }
    }
}
