package com.blackroth.training.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdUtilsTest {

    @Test
    @DisplayName("Valid correlation ID is preserved")
    void testValidPreserved() {
        String input = "corr-gw-999_valid";
        assertEquals(input, CorrelationIdUtils.resolveCorrelationId(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void testBlankGeneratesFallback(String input) {
        String result = CorrelationIdUtils.resolveCorrelationId(input);
        assertNotNull(result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    void testNullGeneratesFallback() {
        String result = CorrelationIdUtils.resolveCorrelationId(null);
        assertNotNull(result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @Test
    void testLongGeneratesFallback() {
        String longInput = "x".repeat(65);
        String result = CorrelationIdUtils.resolveCorrelationId(longInput);
        assertNotEquals(longInput, result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"header\r\nCRLF", "bad val", "<tag>", "bad;injection"})
    void testMalformedGeneratesFallback(String malformed) {
        String result = CorrelationIdUtils.resolveCorrelationId(malformed);
        assertNotEquals(malformed, result);
        assertDoesNotThrow(() -> UUID.fromString(result));
    }
}
