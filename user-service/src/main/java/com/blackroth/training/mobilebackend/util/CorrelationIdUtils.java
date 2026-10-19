package com.blackroth.training.mobilebackend.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Standard utility for resolving and sanitizing Correlation IDs in User Service.
 */
public final class CorrelationIdUtils {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private CorrelationIdUtils() {
    }

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
