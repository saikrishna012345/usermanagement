package com.blackroth.training.mobilebackend.filter;

import com.blackroth.training.mobilebackend.util.TraceContextUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TraceContextFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private TraceContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceContextFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Filter propagates valid incoming traceparent to MDC and response header")
    void testFilterPropagatesValidIncomingTraceparent() throws ServletException, IOException {
        String incomingTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        when(request.getHeader(TraceContextUtils.TRACEPARENT_HEADER)).thenReturn(incomingTraceparent);

        AtomicReference<String> mdcTraceId = new AtomicReference<>();
        AtomicReference<String> mdcSpanId = new AtomicReference<>();
        AtomicReference<String> mdcTraceparent = new AtomicReference<>();

        doAnswer(invocation -> {
            mdcTraceId.set(MDC.get(TraceContextUtils.MDC_TRACE_ID));
            mdcSpanId.set(MDC.get(TraceContextUtils.MDC_SPAN_ID));
            mdcTraceparent.set(MDC.get(TraceContextUtils.MDC_TRACEPARENT));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", mdcTraceId.get());
        assertEquals("00f067aa0ba902b7", mdcSpanId.get());
        assertEquals(incomingTraceparent, mdcTraceparent.get());
        verify(response).setHeader(TraceContextUtils.TRACEPARENT_HEADER, incomingTraceparent);
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(TraceContextUtils.MDC_TRACE_ID), "MDC must be cleared after filter completion");
    }

    @Test
    @DisplayName("Filter generates safe W3C fallback when traceparent is missing")
    void testFilterGeneratesFallbackWhenMissing() throws ServletException, IOException {
        when(request.getHeader(TraceContextUtils.TRACEPARENT_HEADER)).thenReturn(null);

        AtomicReference<String> mdcTraceparent = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcTraceparent.set(MDC.get(TraceContextUtils.MDC_TRACEPARENT));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(mdcTraceparent.get());
        assertTrue(TraceContextUtils.isValidTraceparent(mdcTraceparent.get()));
        verify(response).setHeader(eq(TraceContextUtils.TRACEPARENT_HEADER), eq(mdcTraceparent.get()));
        assertNull(MDC.get(TraceContextUtils.MDC_TRACEPARENT), "MDC must be cleared after filter completion");
    }

    @Test
    @DisplayName("Filter generates safe W3C fallback when traceparent is malformed or all zeros")
    void testFilterGeneratesFallbackWhenMalformed() throws ServletException, IOException {
        String malformed = "00-00000000000000000000000000000000-0000000000000000-01";
        when(request.getHeader(TraceContextUtils.TRACEPARENT_HEADER)).thenReturn(malformed);

        AtomicReference<String> mdcTraceparent = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcTraceparent.set(MDC.get(TraceContextUtils.MDC_TRACEPARENT));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(mdcTraceparent.get());
        assertTrue(TraceContextUtils.isValidTraceparent(mdcTraceparent.get()));
        assertNotEquals(malformed, mdcTraceparent.get());
        verify(response).setHeader(eq(TraceContextUtils.TRACEPARENT_HEADER), eq(mdcTraceparent.get()));
    }

    @Test
    @DisplayName("Filter clears MDC even when downstream filter chain throws an exception")
    void testFilterCleansUpMdcOnException() throws ServletException, IOException {
        when(request.getHeader(TraceContextUtils.TRACEPARENT_HEADER)).thenReturn("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        doThrow(new RuntimeException("Downstream processing failed"))
                .when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () -> filter.doFilterInternal(request, response, filterChain));
        assertNull(MDC.get(TraceContextUtils.MDC_TRACEPARENT), "MDC must be cleared even on unhandled exception");
    }
}
