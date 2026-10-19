package com.blackroth.training.mobilebackend.filter;

import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorrelationIdFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Filter propagates valid incoming X-Correlation-ID to MDC and response header")
    void testFilterPropagatesValidIncomingId() throws ServletException, IOException {
        String incomingId = "corr-test-12345";
        when(request.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER)).thenReturn(incomingId);

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringFilter.set(MDC.get(CorrelationIdUtils.MDC_KEY));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(incomingId, mdcDuringFilter.get());
        verify(response).setHeader(CorrelationIdUtils.CORRELATION_ID_HEADER, incomingId);
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(CorrelationIdUtils.MDC_KEY), "MDC must be cleared after filter completion");
    }

    @Test
    @DisplayName("Filter generates safe UUID fallback when X-Correlation-ID is missing")
    void testFilterGeneratesFallbackWhenMissing() throws ServletException, IOException {
        when(request.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER)).thenReturn(null);

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringFilter.set(MDC.get(CorrelationIdUtils.MDC_KEY));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertNotNull(mdcDuringFilter.get());
        assertDoesNotThrow(() -> UUID.fromString(mdcDuringFilter.get()));
        verify(response).setHeader(eq(CorrelationIdUtils.CORRELATION_ID_HEADER), eq(mdcDuringFilter.get()));
        assertNull(MDC.get(CorrelationIdUtils.MDC_KEY), "MDC must be cleared after filter completion");
    }

    @Test
    @DisplayName("Filter generates safe UUID fallback when X-Correlation-ID is malformed or too long")
    void testFilterGeneratesFallbackWhenMalformed() throws ServletException, IOException {
        String malformedHeader = "bad\r\nHeaderValueWithCRLF";
        when(request.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER)).thenReturn(malformedHeader);

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringFilter.set(MDC.get(CorrelationIdUtils.MDC_KEY));
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        assertNotEquals(malformedHeader, mdcDuringFilter.get());
        assertNotNull(mdcDuringFilter.get());
        assertDoesNotThrow(() -> UUID.fromString(mdcDuringFilter.get()));
        verify(response).setHeader(eq(CorrelationIdUtils.CORRELATION_ID_HEADER), eq(mdcDuringFilter.get()));
        assertNull(MDC.get(CorrelationIdUtils.MDC_KEY));
    }

    @Test
    @DisplayName("Filter clears MDC even when downstream filter chain throws an exception")
    void testFilterCleansUpMdcOnException() throws ServletException, IOException {
        when(request.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER)).thenReturn("corr-exception-test");
        doThrow(new RuntimeException("Downstream processing failed"))
                .when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () -> filter.doFilterInternal(request, response, filterChain));
        assertNull(MDC.get(CorrelationIdUtils.MDC_KEY), "MDC must be cleared even on unhandled exception");
    }
}
