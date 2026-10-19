package com.blackroth.training.mobilebackend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdUtilsTest {

    @Test
    @DisplayName("Valid correlation ID is preserved exactly")
    void testValidCorrelationIdPreserved() {
        String validId = "corr-12345-abc_XYZ";
        String result = CorrelationIdUtils.resolveCorrelationId(validId);
        assertEquals(validId, result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("Empty or whitespace correlation ID falls back to secure UUID")
    void testBlankCorrelationIdGeneratesFallback(String blankInput) {
        String result = CorrelationIdUtils.resolveCorrelationId(blankInput);
        assertNotNull(result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    @DisplayName("Null correlation ID falls back to secure UUID")
    void testNullCorrelationIdGeneratesFallback() {
        String result = CorrelationIdUtils.resolveCorrelationId(null);
        assertNotNull(result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    @DisplayName("Excessively long correlation ID (>64 chars) falls back to secure UUID")
    void testExcessivelyLongCorrelationIdGeneratesFallback() {
        String longInput = "a".repeat(65);
        String result = CorrelationIdUtils.resolveCorrelationId(longInput);
        assertNotEquals(longInput, result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "corr\r\nInjectedHeader",
            "corr id with spaces",
            "<script>alert(1)</script>",
            "corr;SELECT * FROM orders",
            "corr$#@!"
    })
    @DisplayName("Malformed correlation ID containing CRLF, spaces, or special characters falls back to safe UUID")
    void testMalformedCorrelationIdGeneratesFallback(String malformedInput) {
        String result = CorrelationIdUtils.resolveCorrelationId(malformedInput);
        assertNotEquals(malformedInput, result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }
}
