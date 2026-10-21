package com.blackroth.training.mobilebackend.event.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Serializes and deserializes EventEnvelope with OrderEventPayload")
    void testOrderLifecycleEventEnvelope() throws Exception {
        OrderItemPayload item = new OrderItemPayload(
                101L, "Mobile Phone", 2, new BigDecimal("499.99"), new BigDecimal("999.98"));

        OrderEventPayload orderPayload = new OrderEventPayload(
                5001L, 42L, new BigDecimal("999.98"), "CONFIRMED", List.of(item));

        EventEnvelope<OrderEventPayload> envelope = EventEnvelope.create(
                EventTypes.ORDER_CREATED,
                "corr-order-5001",
                "5001",
                orderPayload
        );

        String json = objectMapper.writeValueAsString(envelope);
        assertNotNull(json);
        assertTrue(json.contains("\"eventType\":\"ORDER_CREATED\""));
        assertTrue(json.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(json.contains("\"aggregateId\":\"5001\""));
        assertTrue(json.contains("\"orderId\":5001"));

        EventEnvelope<OrderEventPayload> deserialized = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<OrderEventPayload>>() {});

        assertNotNull(deserialized);
        assertEquals(envelope.eventId(), deserialized.eventId());
        assertEquals(EventTypes.ORDER_CREATED, deserialized.eventType());
        assertEquals("1.0", deserialized.schemaVersion());
        assertEquals("5001", deserialized.aggregateId());
        assertEquals("corr-order-5001", deserialized.correlationId());
        assertEquals(5001L, deserialized.payload().orderId());
        assertEquals(new BigDecimal("999.98"), deserialized.payload().totalAmount());
        assertEquals(1, deserialized.payload().items().size());
        assertEquals("Mobile Phone", deserialized.payload().items().get(0).productName());

        assertDoesNotThrow(deserialized::validate);
        assertDoesNotThrow(deserialized.payload()::validate);
    }

    @Test
    @DisplayName("Serializes and deserializes EventEnvelope with InventoryUpdatedPayload")
    void testInventoryUpdatedEventEnvelope() throws Exception {
        InventoryUpdatedPayload payload = new InventoryUpdatedPayload(
                101L, 5001L, -2, "RESERVE", 18);

        EventEnvelope<InventoryUpdatedPayload> envelope = EventEnvelope.create(
                EventTypes.INVENTORY_UPDATED,
                "corr-inv-101",
                "101",
                payload
        );

        String json = objectMapper.writeValueAsString(envelope);
        assertTrue(json.contains("\"eventType\":\"INVENTORY_UPDATED\""));
        assertTrue(json.contains("\"productId\":101"));

        EventEnvelope<InventoryUpdatedPayload> deserialized = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<InventoryUpdatedPayload>>() {});

        assertNotNull(deserialized);
        assertEquals("101", deserialized.aggregateId());
        assertEquals(-2, deserialized.payload().quantityChanged());
        assertEquals("RESERVE", deserialized.payload().operation());
        assertEquals(18, deserialized.payload().availableStock());

        assertDoesNotThrow(deserialized::validate);
        assertDoesNotThrow(deserialized.payload()::validate);
    }

    @Test
    @DisplayName("Serializes and deserializes EventEnvelope with NotificationRequestedPayload")
    void testNotificationRequestedEventEnvelope() throws Exception {
        NotificationRequestedPayload payload = new NotificationRequestedPayload(
                42L, "EMAIL", "ORDER_CONFIRMATION", "5001",
                "Order #5001 Confirmed", "u***@example.com");

        EventEnvelope<NotificationRequestedPayload> envelope = EventEnvelope.create(
                EventTypes.NOTIFICATION_REQUESTED,
                "corr-notif-5001",
                "42",
                payload
        );

        String json = objectMapper.writeValueAsString(envelope);
        assertTrue(json.contains("\"eventType\":\"NOTIFICATION_REQUESTED\""));
        assertTrue(json.contains("\"templateCode\":\"ORDER_CONFIRMATION\""));

        EventEnvelope<NotificationRequestedPayload> deserialized = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<NotificationRequestedPayload>>() {});

        assertNotNull(deserialized);
        assertEquals("42", deserialized.aggregateId());
        assertEquals("ORDER_CONFIRMATION", deserialized.payload().templateCode());
        assertEquals("u***@example.com", deserialized.payload().maskedContact());

        assertDoesNotThrow(deserialized::validate);
        assertDoesNotThrow(deserialized.payload()::validate);
    }

    @Test
    @DisplayName("Schema evolution: ignores unknown fields in envelope and payload")
    void testSchemaEvolutionForwardCompatibility() throws Exception {
        String jsonWithExtraFields = """
                {
                    "eventId": "evt-ext-99",
                    "eventType": "ORDER_SHIPPED",
                    "schemaVersion": "2.0",
                    "occurredAt": "2026-10-19T10:00:00Z",
                    "correlationId": "corr-ext-99",
                    "aggregateId": "5002",
                    "extraEnvelopeMetadata": "ignored-value",
                    "payload": {
                        "orderId": 5002,
                        "userId": 88,
                        "totalAmount": 120.00,
                        "orderStatus": "SHIPPED",
                        "carrierName": "FedEx",
                        "trackingNumber": "TRK-987654321"
                    }
                }
                """;

        EventEnvelope<OrderEventPayload> deserialized = objectMapper.readValue(
                jsonWithExtraFields, new TypeReference<EventEnvelope<OrderEventPayload>>() {});

        assertNotNull(deserialized);
        assertEquals("evt-ext-99", deserialized.eventId());
        assertEquals("ORDER_SHIPPED", deserialized.eventType());
        assertEquals("5002", deserialized.aggregateId());
        assertEquals(5002L, deserialized.payload().orderId());
        assertEquals("SHIPPED", deserialized.payload().orderStatus());

        assertDoesNotThrow(deserialized::validate);
    }

    @Test
    @DisplayName("Envelope validation enforces mandatory envelope fields")
    void testEnvelopeValidationConstraints() {
        // Missing eventId
        EventEnvelope<String> missingId = new EventEnvelope<>(
                null, "ORDER_CREATED", "1.0", Instant.now(), "c", "1", "data");
        assertThrows(IllegalArgumentException.class, missingId::validate);

        // Blank eventType
        EventEnvelope<String> blankType = new EventEnvelope<>(
                "id-1", "  ", "1.0", Instant.now(), "c", "1", "data");
        assertThrows(IllegalArgumentException.class, blankType::validate);

        // Missing schemaVersion
        EventEnvelope<String> missingVersion = new EventEnvelope<>(
                "id-1", "ORDER_CREATED", null, Instant.now(), "c", "1", "data");
        assertThrows(IllegalArgumentException.class, missingVersion::validate);

        // Null occurredAt
        EventEnvelope<String> nullOccurredAt = new EventEnvelope<>(
                "id-1", "ORDER_CREATED", "1.0", null, "c", "1", "data");
        assertThrows(IllegalArgumentException.class, nullOccurredAt::validate);

        // Blank aggregateId
        EventEnvelope<String> blankAggregate = new EventEnvelope<>(
                "id-1", "ORDER_CREATED", "1.0", Instant.now(), "c", "  ", "data");
        assertThrows(IllegalArgumentException.class, blankAggregate::validate);

        // Null payload
        EventEnvelope<String> nullPayload = new EventEnvelope<>(
                "id-1", "ORDER_CREATED", "1.0", Instant.now(), "c", "1", null);
        assertThrows(IllegalArgumentException.class, nullPayload::validate);
    }

    @Test
    @DisplayName("Payload validation enforces field constraints")
    void testPayloadValidationConstraints() {
        // Negative amount
        OrderEventPayload invalidAmount = new OrderEventPayload(
                1L, 1L, new BigDecimal("-10.00"), "CONFIRMED", null);
        assertThrows(IllegalArgumentException.class, invalidAmount::validate);

        // Invalid productId in inventory payload
        InventoryUpdatedPayload invalidProduct = new InventoryUpdatedPayload(
                -1L, 1L, -1, "RESERVE", 10);
        assertThrows(IllegalArgumentException.class, invalidProduct::validate);

        // Negative availableStock
        InventoryUpdatedPayload negativeStock = new InventoryUpdatedPayload(
                1L, 1L, -1, "RESERVE", -5);
        assertThrows(IllegalArgumentException.class, negativeStock::validate);

        // Missing recipient in notification
        NotificationRequestedPayload invalidNotif = new NotificationRequestedPayload(
                null, "EMAIL", "ORDER_CONFIRMATION", "1", "Subject", "user@test.com");
        assertThrows(IllegalArgumentException.class, invalidNotif::validate);
    }

    @Test
    @DisplayName("Schema version compatibility rules enforce major version matching")
    void testSchemaVersionCompatibilityRules() {
        EventEnvelope<String> v1Envelope = new EventEnvelope<>(
                "id-v1", EventTypes.ORDER_CREATED, "1.0", Instant.now(), "c-1", "1", "payload");
        assertTrue(v1Envelope.isCompatibleSchemaVersion("1"));
        assertTrue(v1Envelope.isSupportedSchemaVersion());
        assertFalse(v1Envelope.isCompatibleSchemaVersion("2"));
        assertDoesNotThrow(() -> v1Envelope.validateWithVersion("1"));

        EventEnvelope<String> v1MinorEnvelope = new EventEnvelope<>(
                "id-v1.2", EventTypes.ORDER_CREATED, "1.2", Instant.now(), "c-1", "1", "payload");
        assertTrue(v1MinorEnvelope.isCompatibleSchemaVersion("1"));
        assertDoesNotThrow(() -> v1MinorEnvelope.validateWithVersion("1"));

        EventEnvelope<String> v2Envelope = new EventEnvelope<>(
                "id-v2", EventTypes.ORDER_CREATED, "2.0", Instant.now(), "c-2", "2", "payload");
        assertFalse(v2Envelope.isCompatibleSchemaVersion("1"));
        assertFalse(v2Envelope.isSupportedSchemaVersion());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> v2Envelope.validateWithVersion("1"));
        assertTrue(ex.getMessage().contains("Unsupported schema version: 2.0"));

        EventEnvelope<String> nullVersionEnvelope = new EventEnvelope<>(
                "id-null", EventTypes.ORDER_CREATED, null, Instant.now(), "c-3", "3", "payload");
        assertFalse(nullVersionEnvelope.isCompatibleSchemaVersion("1"));
    }

    @Test
    @DisplayName("Event types validation identifies known and unsupported event types")
    void testEventTypeValidationRules() {
        assertTrue(EventTypes.isKnownEventType(EventTypes.ORDER_CREATED));
        assertTrue(EventTypes.isKnownEventType(EventTypes.ORDER_CANCELLED));
        assertTrue(EventTypes.isKnownEventType(EventTypes.ORDER_SHIPPED));
        assertTrue(EventTypes.isKnownEventType(EventTypes.ORDER_DELIVERED));
        assertTrue(EventTypes.isKnownEventType(EventTypes.INVENTORY_UPDATED));
        assertTrue(EventTypes.isKnownEventType(EventTypes.NOTIFICATION_REQUESTED));

        assertFalse(EventTypes.isKnownEventType(null));
        assertFalse(EventTypes.isKnownEventType("   "));
        assertFalse(EventTypes.isKnownEventType("UNKNOWN_CUSTOM_EVENT"));

        EventEnvelope<String> validEvent = EventEnvelope.create(
                EventTypes.ORDER_CREATED, "corr", "1", "data");
        assertTrue(validEvent.isSupportedEventType());

        EventEnvelope<String> unknownEvent = EventEnvelope.create(
                "PAYMENT_AUTHORIZED_LEGACY", "corr", "1", "data");
        assertFalse(unknownEvent.isSupportedEventType());
    }

    @Test
    @DisplayName("Malformed JSON payload syntax throws JsonProcessingException")
    void testMalformedJsonPayloadHandling() {
        String malformedJson = "{ \"eventId\": \"evt-broken\", \"eventType\": \"ORDER_CREATED\", \"payload\": ";
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () ->
                objectMapper.readValue(malformedJson, new TypeReference<EventEnvelope<OrderEventPayload>>() {}));

        String invalidTypeJson = """
                {
                    "eventId": "evt-type-mismatch",
                    "eventType": "ORDER_CREATED",
                    "schemaVersion": "1.0",
                    "occurredAt": "not-a-valid-timestamp",
                    "correlationId": "corr",
                    "aggregateId": "1",
                    "payload": { "orderId": "not-a-number" }
                }
                """;
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () ->
                objectMapper.readValue(invalidTypeJson, new TypeReference<EventEnvelope<OrderEventPayload>>() {}));
    }
}
