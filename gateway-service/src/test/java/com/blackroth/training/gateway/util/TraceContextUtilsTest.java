package com.blackroth.training.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextUtilsTest {

    @Test
    @DisplayName("Validates compliant W3C traceparent successfully")
    void testValidTraceparent() {
        String valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertTrue(TraceContextUtils.isValidTraceparent(valid));
        assertEquals(valid, TraceContextUtils.resolveTraceparent(valid));
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", TraceContextUtils.extractTraceId(valid));
        assertEquals("00f067aa0ba902b7", TraceContextUtils.extractSpanId(valid));
    }

    @Test
    @DisplayName("Rejects all-zero traceId and spanId")
    void testRejectsAllZeroIds() {
        String zeroTraceId = "00-00000000000000000000000000000000-00f067aa0ba902b7-01";
        String zeroSpanId = "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01";

        assertFalse(TraceContextUtils.isValidTraceparent(zeroTraceId));
        assertFalse(TraceContextUtils.isValidTraceparent(zeroSpanId));

        // When resolving invalid input, generates valid fallback
        String fallbackTrace = TraceContextUtils.resolveTraceparent(zeroTraceId);
        assertTrue(TraceContextUtils.isValidTraceparent(fallbackTrace));
        assertNotEquals(TraceContextUtils.ALL_ZERO_TRACE_ID, TraceContextUtils.extractTraceId(fallbackTrace));
    }

    @Test
    @DisplayName("Rejects null, blank, and malformed traceparent headers")
    void testRejectsMalformedHeaders() {
        assertFalse(TraceContextUtils.isValidTraceparent(null));
        assertFalse(TraceContextUtils.isValidTraceparent(""));
        assertFalse(TraceContextUtils.isValidTraceparent("   "));
        assertFalse(TraceContextUtils.isValidTraceparent("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")); // unsupported version
        assertFalse(TraceContextUtils.isValidTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7")); // missing flags
        assertFalse(TraceContextUtils.isValidTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\ninjected")); // CRLF
        assertFalse(TraceContextUtils.isValidTraceparent("00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01")); // non-hex character 'g'
    }

    @Test
    @DisplayName("Generates new compliant W3C traceparent")
    void testGeneratesTraceparent() {
        String generated = TraceContextUtils.generateTraceparent();
        assertTrue(TraceContextUtils.isValidTraceparent(generated));
        assertTrue(generated.startsWith("00-"));
        assertTrue(generated.endsWith("-01"));
        assertNotNull(TraceContextUtils.extractTraceId(generated));
        assertNotNull(TraceContextUtils.extractSpanId(generated));
    }
}
