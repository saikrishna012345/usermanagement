package com.blackroth.training.mobilebackend.util;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard utility for resolving, validating, extracting, and generating
 * W3C Trace Context (traceparent) headers across HTTP boundaries in user-service.
 *
 * Conforms strictly to W3C Trace Context Level 1:
 * Format: 00-{32 hex trace-id}-{16 hex parent-id/span-id}-{2 hex trace-flags}
 */
public final class TraceContextUtils {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRACEPARENT = "traceparent";

    public static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    public static final String ALL_ZERO_SPAN_ID = "0000000000000000";

    private static final Pattern W3C_TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceContextUtils() {
    }

    /**
     * Checks if the given traceparent string strictly conforms to W3C Trace Context specification.
     * Rejects null, blank, invalid length, non-hex, unsupported versions, and all-zero IDs.
     *
     * @param traceparent candidate traceparent string
     * @return true if valid W3C traceparent
     */
    public static boolean isValidTraceparent(String traceparent) {
        if (traceparent == null) {
            return false;
        }
        String trimmed = traceparent.trim();
        if (trimmed.length() != 55) {
            return false;
        }
        Matcher matcher = W3C_TRACEPARENT_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return false;
        }
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        if (ALL_ZERO_TRACE_ID.equalsIgnoreCase(traceId) || ALL_ZERO_SPAN_ID.equalsIgnoreCase(spanId)) {
            return false;
        }
        return true;
    }

    /**
     * Resolves a safe, valid W3C traceparent.
     * If valid, returns normalized lowercase traceparent.
     * If missing, blank, malformed, or all-zeros, generates a new valid traceparent.
     *
     * @param rawTraceparent input candidate
     * @return valid normalized W3C traceparent
     */
    public static String resolveTraceparent(String rawTraceparent) {
        if (isValidTraceparent(rawTraceparent)) {
            return rawTraceparent.trim().toLowerCase();
        }
        return generateTraceparent();
    }

    /**
     * Generates a new compliant W3C traceparent with sampled flag (01).
     *
     * @return formatted 00-{traceId}-{spanId}-01
     */
    public static String generateTraceparent() {
        return buildTraceparent(generateTraceId(), generateSpanId(), true);
    }

    public static String generateTraceId() {
        byte[] bytes = new byte[16];
        do {
            RANDOM.nextBytes(bytes);
        } while (isAllZeros(bytes));
        return bytesToHex(bytes);
    }

    public static String generateSpanId() {
        byte[] bytes = new byte[8];
        do {
            RANDOM.nextBytes(bytes);
        } while (isAllZeros(bytes));
        return bytesToHex(bytes);
    }

    public static String buildTraceparent(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId.toLowerCase() + "-" + spanId.toLowerCase() + "-" + (sampled ? "01" : "00");
    }

    public static String extractTraceId(String traceparent) {
        if (traceparent != null) {
            Matcher matcher = W3C_TRACEPARENT_PATTERN.matcher(traceparent.trim());
            if (matcher.matches()) {
                String traceId = matcher.group(1).toLowerCase();
                if (!ALL_ZERO_TRACE_ID.equals(traceId)) {
                    return traceId;
                }
            }
        }
        return null;
    }

    public static String extractSpanId(String traceparent) {
        if (traceparent != null) {
            Matcher matcher = W3C_TRACEPARENT_PATTERN.matcher(traceparent.trim());
            if (matcher.matches()) {
                String spanId = matcher.group(2).toLowerCase();
                if (!ALL_ZERO_SPAN_ID.equals(spanId)) {
                    return spanId;
                }
            }
        }
        return null;
    }

    private static boolean isAllZeros(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
