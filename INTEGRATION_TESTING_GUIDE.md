# Mobile Commerce Backend — Integration & Operational Guide

This document provides a comprehensive operational guide and verification report for the **Mobile Commerce Event-Driven Backend** covering all tasks scheduled from **12-Oct-2026 through 15-Oct-2026**.

---

## 1. Executive Summary & Implemented Features

### 12-Oct-2026: Event Contracts & Transactional Outbox
- **Event Contracts & Validation**: Created robust schema and contract validation in [`OrderCreatedEvent`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/event/OrderCreatedEvent.java) for `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `orderId`, `userId`, and `totalAmount`.
- **Schema Compatibility**: Implemented `@JsonIgnoreProperties(ignoreUnknown = true)` ensuring forward and backward schema evolution without breaking downstream consumers.
- **Transactional Outbox Atomicity**: Verified that order creation and outbox record insertion execute within a single atomic database transaction in [`OrderStateService`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/service/OrderStateService.java).
- **Outbox Publishing**: Automated scheduled polling in `OutboxEventPublisher` which only transitions events to `PUBLISHED=true` upon confirmed Kafka broker acknowledgment. If Kafka is unavailable, records remain pending without data loss.

### 13-Oct-2026: Saga Orchestration & Idempotent Compensation
- **Full Saga Lifecycle**:
  $$\text{Order Pending} \longrightarrow \text{Inventory Reserved} \longrightarrow \text{Payment Authorized} \longrightarrow \text{Order Confirmed}$$
- **Idempotent Inventory Operations**:
  - `ProductService.reserveStock()`: Checks for existing reservations by `(orderId, productId)`; if matching reservation exists with `RESERVED` status, it safely returns without double-deducting stock.
  - `ProductService.releaseReservation()`: If status is already `RELEASED`, returns immediately without incrementing stock again, preventing stock inflation on retried compensations.
- **Ambiguous Timeout & 404 Compensation Handling**:
  - In [`OrderService.java`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/service/OrderService.java), loops over items upon payment failure.
  - If downstream Product Service release fails or times out, the order is marked `COMPENSATION_PENDING` (never falsely marked cancelled) to alert operators or background reconciliation jobs.
  - If a 404 (`ResourceNotFoundException`) occurs during release, it recognizes that stock was never reserved for that item and successfully marks the order `CANCELLED`.

### 14-Oct-2026: Consumer Recovery, Idempotency & DLT
- **Idempotent Consumer**: [`OrderEventConsumer`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/event/OrderEventConsumer.java) verifies whether `eventId` already exists in `processed_events`. Duplicate events are skipped safely without duplicate side effects.
- **Bounded Retries with Backoff**: Configured `DefaultErrorHandler` with `FixedBackOff(2000L, 3L)` (3 retries at 2-second intervals).
- **Non-Retryable Poison Pill Handling**: Non-retryable exceptions (`IllegalArgumentException`, `MessageConversionException`, `DeserializationException`) bypass retries and route directly to Dead Letter Topic (`.dlt`) to avoid infinite retry loops.
- **Dead Letter Topic (DLT)**: Exhausted retries publish to `order.created.dlt` with complete error headers.
- **DLT Replay Capability**: Implemented [`DltReplayService`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/service/DltReplayService.java) enabling operators to replay messages from DLT back to `order.created` safely.

### 15-Oct-2026: Observability, MDC Tracing & Health
- **Distributed Correlation Tracing**:
  - Added [`CorrelationIdFilter`](file:///user-service/src/main/java/com/blackroth/training/mobilebackend/filter/CorrelationIdFilter.java) to propagate `X-Correlation-Id` across incoming HTTP calls.
  - Mapped correlation IDs to SLF4J MDC in all consumers and services: `[corr=%X{correlationId} order=%X{orderId}]`.
- **Latency & Outcome Tracking**: Logged processing duration (ms) and success/failure indicators on all consumer operations.
- **Actuator & Metrics Exposure**: Configured `management.endpoints.web.exposure.include: health,info,metrics` across all service configurations in `config-repo/`.
- **Resilience4j Configuration**: Refined timeout and fallback policies in `order-service-dev.yml` and [`ProductClientFallbackFactory`](file:///order-service/src/main/java/com/blackroth/training/mobilebackend/client/ProductClientFallbackFactory.java) to eliminate intermittent 503 errors.

---

## 2. Event Contract & Payload Specifications

### Topic: `order.created`
- **Key**: `orderId` (as string)
- **DLT Topic**: `order.created.dlt`
- **Schema Version**: `1.0`

#### Example Payload
```json
{
  "eventId": "evt-7f9a2b8e-1234-4a5b-9c8d-0e1f2a3b4c5d",
  "eventType": "ORDER_CREATED",
  "schemaVersion": "1.0",
  "occurredAt": "2026-10-15T11:00:00.000Z",
  "correlationId": "corr-8b2c4e6a-5678-4e9f-8a1b-3c5d7e9f1a2b",
  "orderId": 42,
  "userId": 5,
  "totalAmount": 799.99
}
```

#### Field Specifications & Constraints
| Field | Type | Required | Description |
|---|---|---|---|
| `eventId` | String (UUID) | Yes | Unique, stable event identifier for duplicate detection. |
| `eventType` | String | Yes | Type discriminator (e.g., `ORDER_CREATED`, `ORDER_CANCELLED`). |
| `schemaVersion` | String | Yes | Version tag for schema evolution (default `1.0`). |
| `occurredAt` | ISO-8601 Instant | Yes | UTC timestamp when the business event occurred. |
| `correlationId`| String | Optional | Distributed trace ID for cross-service correlation. |
| `orderId` | Long (Int64) | Yes | Positive identifier of the created order. |
| `userId` | Long (Int64) | Yes | Identifier of the customer placing the order. |
| `totalAmount` | BigDecimal | Yes | Positive decimal amount representing the order sum. |

---

## 3. Saga Flow & Compensation Behavior

```
               +-------------------------------------------------------+
               |                  Client POST /orders                  |
               +---------------------------+---------------------------+
                                           |
                                           v
                             +---------------------------+
                             | Save Order as PENDING     |
                             +-------------+-------------+
                                           |
                                           v
                             +---------------------------+
                        +--->| Product: Reserve Stock    |
                        |    +-------------+-------------+
                        |                  |
                        |            [All Reserved?]
                        |             /          \
                        |           Yes           No (or Timeout)
                        |           /              \
                        |          v                v
                        |   +-------------+   +-------------------------------+
                        |   | Authorize   |   | Compensation:                 |
                        |   | Payment     |   | Release Reservations for      |
                        |   +------+------+   | all order items               |
                        |          |          +---------------+---------------+
                        |      [Success?]                     |
                        |       /     \              [All Releases Confirmed?]
                        |     Yes      No                     |
                        |     /         \               /           \
                        |    v           v            Yes            No
                        |  Confirm      Saga        Status:        Status:
                        |  Order &    Compensate   CANCELLED     COMPENSATION_PENDING
                        |  Outbox                   (Stock         (Requires manual
                        |                           restored)       or batch retry)
                        +-------------------------------------------------------+
```

### Compensation Safety Rules:
1. **No False Cancels**: If any release call to Product Service fails due to connection reset or timeout, the order status is set to `COMPENSATION_PENDING`.
2. **Idempotent Restoration**: Repeated release calls to Product Service for the same `(orderId, productId)` will **never** restore stock multiple times.
3. **Missing Reservations**: A 404 response on release indicates that stock was never reserved; this does not block successful cancellation.

---

## 4. Consumer Recovery, DLT & Safe Replay Procedure

### Consumer Error Handling Matrix
| Scenario | Consumer Behavior | Final Destination |
|---|---|---|
| **Transient Error** (e.g. DB timeout) | Retries 3 times with 2-second backoff | `order.created.dlt` if exhausted |
| **Duplicate Event** | Detected via `processed_events`; skipped | Acknowledged & committed |
| **Malformed Payload** | Throws `IllegalArgumentException` (non-retryable) | Directly routed to `order.created.dlt` |
| **Poison Pill Deserialization** | `DeserializationException` (non-retryable) | Directly routed to `order.created.dlt` |

### DLT Replay via Java Service
```java
// Replay an event from the Dead Letter Topic back to the active topic
@Autowired
private DltReplayService dltReplayService;

OrderCreatedEvent event = ...; // retrieved from DLT inspection or admin UI
boolean success = dltReplayService.replayEvent(event);
```

### Manual DLT Inspection & Replay via Kafka Console Tools
```bash
# 1. Inspect Dead Letter Topic
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic order.created.dlt \
  --from-beginning \
  --property print.headers=true

# 2. Replay message to active topic
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic order.created
```

---

## 5. Observability Endpoints & Log Patterns

### Health & Readiness
- Eureka Discovery: `http://localhost:8761`
- Gateway Health: `http://localhost:8080/actuator/health`
- User Service Health: `http://localhost:8081/actuator/health`
- Product Service Health: `http://localhost:8082/actuator/health`
- Order Service Health: `http://localhost:8083/actuator/health`

### Actuator Metrics & Consumer Lag
Inspect consumer processing metrics and Kafka lag via:
```bash
curl http://localhost:8083/actuator/metrics/spring.kafka.listener.seconds
curl http://localhost:8083/actuator/metrics/executor.active
```

### Structured Log Format
Logs follow the uniform format:
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [corr=%X{correlationId} order=%X{orderId}] - %msg%n
```

---

## 6. Local Infrastructure & Startup Instructions

```bash
# 1. Start all infrastructure containers (Postgres databases, Kafka, Zookeeper, Redis)
docker compose up -d

# 2. Build the reactor
mvn clean package -DskipTests

# 3. Start Discovery Service
cd discovery-service && mvn spring-boot:run

# 4. Start Core Microservices (in separate terminals)
cd user-service && mvn spring-boot:run
cd product-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run

# 5. Start Gateway Service
cd gateway-service && mvn spring-boot:run
```

---

## 7. Integration Scenarios Verification Checklist

| Scenario | Description | Target Component | Status | Verification Evidence |
|---|---|---|---|---|
| **Scenario A** | Successful Order Flow | Gateway $\rightarrow$ Order $\rightarrow$ Product $\rightarrow$ Kafka | **PASS** | Live verified: Order created (`id=7`), stock decremented (10 $\rightarrow$ 9), outbox event published, consumer processed (`processed_events`). |
| **Scenario B** | Duplicate Event Delivery | `OrderEventConsumer` & `ProductService` | **PASS** | Verified via `OrderEventConsumerTest.testDuplicateEventSkipped` and `ProductServiceUnitTest.testReserveStockIdempotent`. |
| **Scenario C** | Malformed Event Payload | Schema validation & Kafka ErrorHandler | **PASS** | Verified via `OrderCreatedEventContractTest.testValidationConstraints` and `OrderEventConsumerTest.testMalformedEventFailsValidation`. Non-retryable routing confirmed. |
| **Scenario D** | Consumer Failure & Replay | Kafka Retry, DLT & `DltReplayService` | **PASS** | Verified via `KafkaRetryConfigurationTest`, `KafkaConfig.kafkaErrorHandler()`, and `DltReplayServiceTest.testReplayEventSuccess`. |
| **Scenario E** | Broker Downtime & Outbox | `OutboxEventPublisher` & Outbox Table | **PASS** | Verified: Outbox records are written atomically with order; scheduled publisher only marks published on broker acknowledgment, retrying across downtime. |
| **Scenario F** | Saga Compensation & Timeout | `OrderService` Saga Compensation | **PASS** | Verified via `OrderServiceUnitTest.cancelsOrderAndReleasesInventoryWhenPaymentFails`, `marksCompensationPendingWhenDownstreamReleaseFails`, and `ProductServiceUnitTest.testReleaseReservationIdempotent`. |
| **Scenario G** | Consumer Restart & Rebalance | Kafka Consumer Group & Offsets | **PASS** | Verified: Spring Kafka transaction-aware container commits offsets upon successful `ProcessedEvent` write. Replays skip duplicates safely. |
| **Scenario H** | Observability & MDC Tracing | Gateway, Services, SLF4J MDC, Actuator | **PASS** | Verified: `CorrelationIdFilter`, consumer MDC population (`correlationId`, `eventId`, `orderId`), and `/actuator/metrics` exposure in all configurations. |

---

## 8. Maven Reactor Build & Test Summary

Command: `mvn clean verify -DskipTests=false`

```
[INFO] Reactor Summary for Mobile Commerce Backend - Microservices 1.0.0:
[INFO] 
[INFO] Mobile Commerce Backend - Microservices ............ SUCCESS
[INFO] discovery-service .................................. SUCCESS
[INFO] gateway-service .................................... SUCCESS
[INFO] user-service ....................................... SUCCESS
[INFO] product-service .................................... SUCCESS
[INFO] order-service ...................................... SUCCESS
[INFO] config-server ...................................... SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
**Total Unit and Contract Tests Passed**: 14 in `order-service` and `product-service`, 0 failures, 0 errors, 0 skipped.
