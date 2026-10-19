package com.blackroth.training.gateway.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Standard utility for resolving and sanitizing Correlation IDs at API Gateway.
 */
public final class CorrelationIdUtils {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private CorrelationIdUtils() {
    }

    /**
     * Resolves a safe correlation ID from the incoming HTTP header.
     * Sanitizes against empty values, CRLF injection, special characters, and excessive length.
     * Generates a secure UUID fallback if invalid or absent.
     *
     * @param input raw header value
     * @return safe, validated correlation ID
     */
    public static String resolveCorrelationId(String input) {
        if (input != null) {
            String trimmed = input.trim();
            if (!trimmed.isEmpty()
                    && trimmed.length() <= MAX_CORRELATION_ID_LENGTH
                    && VALID_CORRELATION_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
