package com.blackroth.training.mobilebackend.event.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        String schemaVersion,
        Instant occurredAt,
        String correlationId,
        String aggregateId,
        T payload
) {
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public static <T> EventEnvelope<T> create(
            String eventType,
            String correlationId,
            String aggregateId,
            T payload
    ) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_SCHEMA_VERSION,
                Instant.now(),
                correlationId,
                aggregateId,
                payload
        );
    }

    public boolean isSupportedEventType() {
        return EventTypes.isKnownEventType(this.eventType);
    }

    public boolean isCompatibleSchemaVersion(String expectedMajorVersion) {
        if (schemaVersion == null || schemaVersion.isBlank() || expectedMajorVersion == null) {
            return false;
        }
        String major = schemaVersion.split("\\.")[0].trim();
        return major.equals(expectedMajorVersion.trim());
    }

    public boolean isSupportedSchemaVersion() {
        return isCompatibleSchemaVersion("1");
    }

    public void validate() {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required and cannot be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required and cannot be blank");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required and cannot be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required and cannot be null");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required and cannot be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required and cannot be null");
        }
    }

    public void validateWithVersion(String expectedMajorVersion) {
        validate();
        if (expectedMajorVersion != null && !isCompatibleSchemaVersion(expectedMajorVersion)) {
            throw new IllegalArgumentException("Unsupported schema version: " + schemaVersion
                    + ". Expected major version: " + expectedMajorVersion);
        }
    }
}
