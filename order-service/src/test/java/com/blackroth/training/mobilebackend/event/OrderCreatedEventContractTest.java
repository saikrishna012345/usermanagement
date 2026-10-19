package com.blackroth.training.mobilebackend.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OrderCreatedEventContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Serializes and deserializes valid OrderCreatedEvent correctly")
    void testSerializationAndDeserialization() throws Exception {
        OrderCreatedEvent original = new OrderCreatedEvent(
                "evt-100",
                "ORDER_CREATED",
                "1.0",
                Instant.parse("2026-10-15T10:00:00Z"),
                "corr-12345",
                42L,
                5L,
                new BigDecimal("99.99")
        );

        String json = objectMapper.writeValueAsString(original);
        assertNotNull(json);
        assertTrue(json.contains("\"eventId\":\"evt-100\""));
        assertTrue(json.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(json.contains("\"correlationId\":\"corr-12345\""));

        OrderCreatedEvent deserialized = objectMapper.readValue(json, OrderCreatedEvent.class);
        assertEquals(original.eventId(), deserialized.eventId());
        assertEquals(original.eventType(), deserialized.eventType());
        assertEquals(original.schemaVersion(), deserialized.schemaVersion());
        assertEquals(original.correlationId(), deserialized.correlationId());
        assertEquals("42", deserialized.aggregateId());
        assertEquals(original.orderId(), deserialized.orderId());
        assertEquals(original.userId(), deserialized.userId());
        assertEquals(original.totalAmount(), deserialized.totalAmount());
        assertDoesNotThrow(deserialized::validate);
    }

    @Test
    @DisplayName("Ignores unknown fields to maintain backwards and forwards compatibility")
    void testSchemaEvolutionCompatibility() throws Exception {
        String jsonWithExtraFields = """
                {
                    "eventId": "evt-200",
                    "eventType": "ORDER_CREATED",
                    "schemaVersion": "2.0",
                    "occurredAt": "2026-10-15T10:00:00Z",
                    "correlationId": "corr-999",
                    "orderId": 50,
                    "userId": 10,
                    "totalAmount": 150.00,
                    "loyaltyPointsEarned": 15,
                    "discountCode": "AUTUMN2026"
                }
                """;

        OrderCreatedEvent event = objectMapper.readValue(jsonWithExtraFields, OrderCreatedEvent.class);
        assertNotNull(event);
        assertEquals("evt-200", event.eventId());
        assertEquals(50L, event.orderId());
        assertDoesNotThrow(event::validate);
    }

    @Test
    @DisplayName("Fails validation when required fields are missing or invalid")
    void testValidationConstraints() {
        // Missing eventId
        OrderCreatedEvent missingEventId = new OrderCreatedEvent(
                null, "ORDER_CREATED", "1.0", Instant.now(), "c1", 1L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, missingEventId::validate);

        // Blank eventType
        OrderCreatedEvent blankEventType = new OrderCreatedEvent(
                "evt-1", "   ", "1.0", Instant.now(), "c1", 1L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, blankEventType::validate);

        // Missing schemaVersion
        OrderCreatedEvent missingSchemaVersion = new OrderCreatedEvent(
                "evt-1", "ORDER_CREATED", null, Instant.now(), "c1", 1L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, missingSchemaVersion::validate);

        // Missing occurredAt
        OrderCreatedEvent missingOccurredAt = new OrderCreatedEvent(
                "evt-1", "ORDER_CREATED", "1.0", null, "c1", 1L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, missingOccurredAt::validate);

        // Invalid orderId
        OrderCreatedEvent invalidOrderId = new OrderCreatedEvent(
                "evt-1", "ORDER_CREATED", "1.0", Instant.now(), "c1", -5L, 1L, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, invalidOrderId::validate);
    }
}
