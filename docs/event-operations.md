# Event Operations and Incident Runbooks

## Overview
This runbook covers observability, incident diagnosis, recovery procedures, metrics, and event operations across the Mobile Commerce Microservices backend.

Services covered:
- **API Gateway** (`8080`): Ingress routing, security enforcement, and Correlation ID initiation/sanitization.
- **User Service** (`8081`): Authentication, user lifecycle, and role assignment.
- **Product Service** (`8082`): Product catalog, inventory management, stock reservation, commit, and release.
- **Order Service** (`8083`): Order lifecycle, Outbox event generation/publication, Kafka consumer, Saga orchestration, and compensation recovery.
- **Config Server** (`8888`): Centralized native configuration.
- **Apache Kafka** (`9092` / `29092`): Event streaming, dead-letter routing, and consumer group offset management.
- **PostgreSQL Databases** (`user_db`: 5433, `product_db`: 5434, `order_db`: 5435): Transactional outbox, processed event tracking, and business entities.

---

## 1. Correlation IDs and Structured Logging

### 1.1 Correlation ID Propagation Architecture
Every business transaction is identified by a unique `X-Correlation-ID` header:
1. **Gateway Ingress:** `CorrelationIdGlobalFilter` inspects incoming HTTP requests for `X-Correlation-ID`.
   - If present, it validates the format against `^[A-Za-z0-9_-]{1,64}$` to prevent log injection.
   - If missing or malformed, a secure random UUID is generated.
   - The sanitized correlation ID is injected into the downstream request header and returned in the HTTP response header.
2. **Microservice Ingress:** `CorrelationIdFilter` on `order-service`, `product-service`, and `user-service` populates SLF4J MDC (`MDC.put("correlationId", id)`).
3. **Inter-Service REST:** `CorrelationIdFeignInterceptor` attaches the active MDC correlation ID to all outbound OpenFeign requests (e.g. `OrderService` -> `ProductClient`).
4. **Outbox Persistence:** `OrderStateService` persists the correlation ID inside the canonical JSON event payload (`OrderCreatedEvent.correlationId`).
5. **Kafka Publishing:** `CorrelationIdProducerInterceptor` and `OutboxEventPublisher` attach `X-Correlation-ID` to Kafka record headers and populate MDC during publishing.
6. **Kafka Consumption:** `OrderEventConsumer` reads `X-Correlation-ID` from Kafka headers (with fallback to payload `event.correlationId()`), resolves/sanitizes it, and populates MDC for the consumer thread.

### 1.2 Structured Log Format
Logs follow a unified structured format configured via console pattern:
`%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} %clr(%5p) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr([traceId=%X{traceId:-} spanId=%X{spanId:-} corr=%X{correlationId:-} order=%X{orderId:-}]){yellow} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n%wEx`

Key structured fields included across event lifecycles:
- `correlationId`: Business transaction correlation identifier.
- `traceId`: W3C distributed tracing trace identifier.
- `spanId`: W3C distributed tracing span identifier.
- `service`: Originating microservice (`gateway-service`, `order-service`, `product-service`).
- `orderId`: Business aggregate identifier.
- `eventId`: Unique event UUID.
- `eventType`: Canonical event contract type (e.g. `ORDER_CREATED`, `ORDER_CANCELLED`, `ORDER_SHIPPED`, `ORDER_DELIVERED`).
- `topic`: Kafka topic name.
- `partition`: Kafka partition number.
- `offset`: Kafka message offset.
- `attempt`: Delivery attempt number (e.g. `1/5`).
- `durationMs`: Execution duration in milliseconds.
- `outcome`: Standardized machine-readable result:
  - `ORDER_CREATED`: Order persisted in PENDING status.
  - `ORDER_CONFIRMED`: Order confirmed after payment authorization.
  - `ORDER_CANCELLED`: Order cancelled after inventory release.
  - `ORDER_SHIPPED`: Order transitioned to SHIPPED.
  - `ORDER_DELIVERED`: Order transitioned to DELIVERED.
  - `OUTBOX_SAVED`: Event payload persisted in PostgreSQL `outbox_events` table.
  - `PUBLISHED`: Outbox event published to Kafka broker.
  - `RETRY_SCHEDULED`: Outbox publish transient failure; exponential backoff scheduled.
  - `FAILED`: Outbox event reached maximum retries (5) and marked permanently failed.
  - `RESERVED`: Inventory reserved in `product-service`.
  - `ALREADY_RESERVED`: Idempotent reservation match.
  - `RELEASED`: Inventory reservation released back to available stock.
  - `ALREADY_RELEASED`: Idempotent release match.
  - `COMMITTED`: Inventory reservation finalized.
  - `RECEIVED`: Kafka event received by listener.
  - `PROCESSED`: Kafka event processed and recorded in `processed_events`.
  - `SKIPPED_DUPLICATE`: Kafka event already exists in `processed_events`; skipped.
  - `RETRY_ATTEMPT`: Kafka listener retry attempt executed by `DefaultErrorHandler`.
  - `DLT_ROUTED`: Non-retryable or retry-exhausted event routed to dead-letter topic.
  - `REPLAY_ATTEMPTED`: Operator initiated DLT replay.
  - `REPLAY_SUCCESS`: Replay published to primary topic.
  - `REPLAY_FAILED`: Replay failed.
  - `SAGA_COMPENSATION_TRIGGERED`: Downstream Saga step failed; inventory compensation initiated.
  - `SAGA_COMPENSATION_COMPLETED`: Inventory released synchronously; order marked CANCELLED.
  - `SAGA_COMPENSATION_PENDING`: Downstream release timed out or failed; order held in COMPENSATION_PENDING.
  - `SAGA_RECOVERY_STARTED`: Periodic recovery poller queried COMPENSATION_PENDING orders.
  - `SAGA_RECOVERY_SUCCESS`: Recovery released downstream reservations and transitioned order to CANCELLED.

### 1.3 Security & Secret Redaction
- Log lines never contain passwords, tokens, JWTs, card numbers, or authorization headers.
- `OutboxEventPublisher.sanitizeErrorMessage()` strips sensitive patterns (`password=***`, `secret=***`, `token=***`, `authorization=***`) prior to persisting errors in the outbox table or outputting error logs.

---

## 2. Metrics and Alerting

### 2.1 Metric Catalog

| Metric Name | Type | Tags (Bounded Cardinality) | Description |
|---|---|---|---|
| `kafka.consumer.fetch.manager.records.lag.max` | Gauge | `client.id` | Maximum record lag across all assigned topic partitions (supported Kafka native metric). |
| `kafka.consumer.fetch.manager.records.lag` | Gauge | `client.id`, `topic`, `partition` | Partition-level consumer lag (supported Kafka native metric). |
| `events.outbox.pending` | Gauge | None | Number of pending outbox events awaiting publication in PostgreSQL. |
| `events.outbox.failed` | Gauge | None | Number of permanently failed outbox events (attempts >= 5). |
| `events.outbox.published` | Counter | `topic`, `event_type`, `outcome` | Total outbox publish attempts (`published`, `retry_scheduled`, `failed`). |
| `events.outbox.duration` | Timer | `topic`, `event_type`, `outcome` | Latency of outbox publishing in milliseconds. |
| `events.consumer.processed` | Counter | `topic`, `event_type`, `outcome` | Total events processed by Kafka consumers (`success`, `duplicate`, `failure`, `dlt_routed`). |
| `events.consumer.duration` | Timer | `topic`, `event_type`, `outcome` | Consumer processing execution duration in milliseconds. |
| `events.consumer.retries` | Counter | `topic`, `event_type` | Total consumer retry attempts executed by `DefaultErrorHandler`. |
| `events.consumer.dlt` | Counter | `topic`, `event_type`, `reason` | Total events routed to dead-letter topics (`validation_error`, `deserialization_error`, `null_payload`, `exhausted_retries`). |
| `events.dlt.replay` | Counter | `topic`, `outcome` | Total DLT event replay attempts (`success`, `failure`). |
| `saga.compensation.pending` | Gauge | None | Orders currently in `COMPENSATION_PENDING` status awaiting recovery. |
| `saga.compensation.total` | Counter | `outcome` | Total Saga compensation operations (`success`, `failed`, `recovered`). |
| `health_status` | Gauge | `component` | Component health state (`1` = UP, `0` = DOWN). |

### 2.2 Endpoints and Access
Actuator and Prometheus endpoints are secured by Role-Based Access Control (RBAC):
- Public Health Check: `GET http://localhost:8083/actuator/health` (no authentication required).
- Comprehensive Metrics: `GET http://localhost:8083/actuator/metrics` (Requires `ROLE_ADMIN` JWT).
- Prometheus Scrape: `GET http://localhost:8083/actuator/prometheus` (Requires `ROLE_ADMIN` JWT or private scraper network).
- Individual Metric Query: `GET http://localhost:8083/actuator/metrics/events.outbox.pending`.

Example curl command:
```bash
# Scrape Prometheus metrics from Order Service
curl -H "Authorization: Bearer <ADMIN_JWT_TOKEN>" http://localhost:8083/actuator/prometheus

# Query specific consumer lag metric
curl -H "Authorization: Bearer <ADMIN_JWT_TOKEN>" http://localhost:8083/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max

# Check service health and downstream dependencies
curl http://localhost:8083/actuator/health
```

### 2.3 Prometheus Alert Rules & Thresholds

Rules are defined in `monitoring/prometheus/alerts.yml`:

```yaml
groups:
  - name: event-driven-microservices-alerts
    rules:
      - alert: KafkaConsumerLagGrowth
        expr: kafka_consumer_fetch_manager_records_lag_max > 100
        for: 2m
        labels:
          severity: warning
          service: order-service
        annotations:
          summary: "High Kafka Consumer Lag Detected"
          description: "Kafka consumer lag has exceeded 100 records for 2 minutes (current lag: {{ $value }})."

      - alert: ConsumerLagCritical
        expr: kafka_consumer_fetch_manager_records_lag_max > 500
        for: 1m
        labels:
          severity: critical
          service: order-service
        annotations:
          summary: "Critical Kafka Consumer Lag Backlog"
          description: "Kafka consumer lag has exceeded 500 records. Risk of SLA breach or partition starvation."

      - alert: PoisonMessagesDetected
        expr: increase(events_consumer_dlt_total[2m]) > 0
        labels:
          severity: critical
          service: order-service
        annotations:
          summary: "Poison Message Routed to Dead Letter Topic"
          description: "Poison or unprocessable message routed to DLT. Requires operational inspection."

      - alert: ConsumerFailureRateHigh
        expr: rate(events_consumer_processed_total{outcome="failure"}[5m]) / rate(events_consumer_processed_total[5m]) > 0.05
        for: 2m
        labels:
          severity: warning
          service: order-service
        annotations:
          summary: "Consumer Error Rate Exceeds 5%"
          description: "Over 5% of consumer events are failing."

      - alert: OutboxBacklogGrowing
        expr: events_outbox_pending > 50
        for: 3m
        labels:
          severity: warning
          service: order-service
        annotations:
          summary: "Outbox Pending Events Backlog Growing"
          description: "More than 50 events pending publication for >3 minutes."

      - alert: OutboxTerminalFailuresDetected
        expr: events_outbox_failed > 0 or increase(events_outbox_published_total{outcome="failed"}[5m]) > 0
        labels:
          severity: critical
          service: order-service
        annotations:
          summary: "Outbox Event Publishing Exhausted Retries"
          description: "Outbox events have exceeded maximum retries and marked FAILED."

      - alert: SagaCompensationsPending
        expr: saga_compensation_pending > 0
        for: 5m
        labels:
          severity: warning
          service: order-service
        annotations:
          summary: "Orders Pending Saga Compensation Recovery"
          description: "Orders have remained in COMPENSATION_PENDING for over 5 minutes."

      - alert: DownstreamProductServiceUnavailable
        expr: health_status{component="productService"} == 0
        for: 1m
        labels:
          severity: critical
          service: order-service
        annotations:
          summary: "Product Service Downstream Dependency Unavailable"
          description: "ProductService circuit breaker is OPEN or health check failed."

      - alert: KafkaBrokerUnavailable
        expr: health_status{component="kafka"} == 0
        for: 1m
        labels:
          severity: critical
          service: order-service
        annotations:
          summary: "Apache Kafka Broker Unavailable"
          description: "Kafka health indicator reported DOWN. Outbox events will queue in PostgreSQL."
```

---

## 3. Incident Runbooks

### Runbook 1: Poison Messages and Dead-Letter Handling

#### Symptoms:
- Alert `PoisonMessagesDetected` fires.
- Metric `events.consumer.dlt` incremented.
- Log message with `outcome=DLT_ROUTED`:
  `ERROR ... Consumer event validation failed: eventId=..., error=..., outcome=DLT_ROUTED`

#### Diagnosis:
1. Locate the dead-letter topic corresponding to the source topic:
   - Primary: `order.created` -> DLT: `order.created.dlt`
   - Primary: `order.cancelled` -> DLT: `order.cancelled.dlt`
   - Primary: `order.shipped` -> DLT: `order.shipped.dlt`
   - Primary: `order.delivered` -> DLT: `order.delivered.dlt`
   - Primary: `inventory.updated` -> DLT: `inventory.updated.dlt`
   - Primary: `notification.requested` -> DLT: `notification.requested.dlt`
2. Inspect the quarantined record using Kafka console consumer:
```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server kafka:29092 \
  --topic order.created.dlt \
  --from-beginning \
  --property print.headers=true \
  --property print.key=true \
  --max-messages 5
```
3. Inspect Kafka headers on the dead-letter record:
   - `kafka_dlt-original-topic`: Source topic name.
   - `kafka_dlt-original-partition`: Partition number where error occurred.
   - `kafka_dlt-original-offset`: Original offset.
   - `kafka_dlt-exception-message`: Detailed root-cause error (e.g. `orderId must be a positive number`, `DeserializationException`).
   - `X-Correlation-ID`: Correlation identifier to cross-reference service logs.

#### Remediation & Safe Replay:
1. **Identify Root Cause:**
   - Schema incompatibility: Deploy backward-compatible DTO update if payload version mismatch.
   - Malformed data from upstream: Verify client validation rules.
   - Transient database deadlock: The `FixedBackOff(2000L, 3L)` handles transient errors; reaching DLT indicates a deterministic error or exhausted retry.
2. **Replay via Administrative REST API:**
   Once the fix is deployed, authorized operators replay the quarantined event using `OrderController`:
```bash
curl -X POST http://localhost:8083/api/v1/orders/admin/dlt/replay \
  -H "Authorization: Bearer <ADMIN_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-poison-1",
    "eventType": "ORDER_CREATED",
    "schemaVersion": "1.0",
    "occurredAt": "2026-10-21T18:00:00Z",
    "correlationId": "corr-poison-1",
    "orderId": 1003,
    "userId": 101,
    "totalAmount": 25.00
  }'
```
3. **Idempotency Guarantee:**
   - If the event was already partially processed or committed, `OrderEventConsumer` checks `processedEventRepository.existsByEventId(eventId)`.
   - Replaying an existing event outputs `outcome=SKIPPED_DUPLICATE` without creating duplicate orders or double-charging inventory.

---

### Runbook 2: Consumer Lag Growth

#### Symptoms:
- Alert `KafkaConsumerLagGrowth` or `ConsumerLagCritical` fires.
- Metric `kafka.consumer.fetch.manager.records.lag.max` rising above threshold (>100 or >500).

#### Diagnosis:
1. Check Kafka consumer group status and lag distribution across partitions:
```bash
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server kafka:29092 \
  --describe \
  --group order-service
```
2. Identify the bottleneck:
   - **Scenario A (Uneven lag):** One partition has high lag while others are zero.
     - *Cause:* Non-uniform partitioning keys or hot aggregate ID. Verify partition key distribution.
   - **Scenario B (Slow processing throughput):** Lag is growing across all partitions.
     - Check `events.consumer.duration` timer in Prometheus or Actuator (`/actuator/metrics/events.consumer.duration`).
     - Check downstream service latency (e.g. `product-service` inventory checks or PostgreSQL connection pool contention).
   - **Scenario C (Consumer instance failure):** Consumer group members fewer than partition count.
     - Check Eureka registration: `http://localhost:8761/eureka/apps/ORDER-SERVICE`.

#### Remediation:
1. If downstream `product-service` is slow, investigate database query performance or cache hit rates (`@Cacheable(value = "products")`).
2. Scale `order-service` consumer instances horizontally up to the topic's partition count (topics are partitioned to support parallel consumer instances).
3. Verify offset commit mode: `ConcurrentKafkaListenerContainerFactory` commits offsets upon successful transaction completion (`@Transactional`).

---

### Runbook 3: Kafka Broker or Downstream Service Outage

#### Symptoms:
- Alert `KafkaBrokerUnavailable` or `DownstreamProductServiceUnavailable` fires.
- Outbox events accumulate in `PENDING` status.
- Circuit breaker for `product-service` trips to `OPEN`.

#### Broker Outage Behavior & Verification:
1. **Outbox Decoupling:**
   - Order creation transactions in `OrderService` write the order and the outbox event in the same atomic PostgreSQL transaction.
   - When Kafka is DOWN, orders continue to be accepted and saved as `CONFIRMED` and outbox records as `PENDING`.
   - `OutboxEventPublisher` attempts to send to Kafka, catches connection timeout/error, increments `attempts`, and calculates exponential backoff:
     `nextAttemptAt = now + min(60s, 2^attempts)`
   - No event is discarded. Events accumulate safely in `outbox_events`.
2. **Broker Restoration:**
   - Check broker container:
```bash
docker restart kafka
docker exec -it kafka kafka-broker-api-versions --bootstrap-server kafka:29092
```
   - When the broker becomes reachable, `OutboxEventPublisher` scheduled task (`fixed-delay-ms: 2000`) detects pending events whose `nextAttemptAt <= now` and transmits them in chronological order (`orderByCreatedAtAsc`).
   - Gauge `events.outbox.pending` drains back to 0.

#### Downstream Product Service Outage Behavior & Saga Recovery:
1. **Failure Detection & Compensation:**
   - When `product-service` is down, OpenFeign calls fail or Resilience4j circuit breaker opens.
   - `OrderService.createOrder` catches the exception and attempts compensation:
     Calls `productClient.releaseReservation` for any items that might have succeeded before the failure.
   - If downstream release fails (e.g. network partition), order transitions to `COMPENSATION_PENDING`:
     `log.warn("Order state transition: orderId={}, previousStatus=PENDING, newStatus=COMPENSATION_PENDING, outcome=SAGA_COMPENSATION_PENDING")`
     Metric `saga.compensation.pending` is incremented.
2. **Saga Recovery Poller:**
   - `SagaCompensationRecoveryService` runs periodically (`fixed-delay-ms: 10000`).
   - It queries `orderRepository.findByStatus(OrderStatus.COMPENSATION_PENDING)`.
   - For each order, it re-attempts `productClient.releaseReservation(productId, orderId)`.
   - `ProductService.releaseReservation` is completely idempotent (returns success if already released or not found).
   - Once all items are verified released, `orderStateService.markCancelled(orderId)` transitions the order to `CANCELLED` and emits an `ORDER_CANCELLED` outbox event.
   - Metric `saga.compensation.total{outcome="recovered"}` increments.

---

## 4. Practical Transaction Trace Evidence

### 4.1 Trace Walkthrough
A complete business transaction was traced through the architecture:

```
Mobile Client
    │
    │ [POST /api/v1/orders] Header: X-Correlation-ID: corr-test-trace-12345
    ▼
API Gateway (8080)
    │ CorrelationIdGlobalFilter validates & logs:
    │ "Gateway request: correlationId=corr-test-trace-12345, method=POST, path=/api/v1/orders"
    ▼
Order Service (8083)
    │ 1. CorrelationIdFilter populates MDC: [corr=corr-test-trace-12345]
    │ 2. OrderService.createOrder saves PENDING order:
    │    "Order created: orderId=1001, userId=101, totalAmount=199.99, status=PENDING, outcome=ORDER_CREATED"
    │ 3. CorrelationIdFeignInterceptor forwards X-Correlation-ID to Product Service:
    │    ProductClient.reserveStock(productId=501, orderId=1001, quantity=2)
    ▼
Product Service (8082)
    │ CorrelationIdFilter receives X-Correlation-ID: corr-test-trace-12345
    │ ProductService reserves stock and logs:
    │ "Inventory reserved successfully: orderId=1001, productId=501, quantity=2, remainingStock=98, service=product-service, outcome=RESERVED"
    ▼
Order Service (8083)
    │ 4. OrderStateService confirms order & creates outbox event:
    │    "Outbox event created: eventId=7d2dcbc1-37c9-4681-a88b-cc461c3e879a, eventType=ORDER_CREATED, topic=order.created, orderId=1001, correlationId=corr-test-trace-12345, status=PENDING, outcome=OUTBOX_SAVED"
    │    "Order state transition: orderId=1001, previousStatus=PENDING, newStatus=CONFIRMED, outcome=ORDER_CONFIRMED"
    ▼
Outbox Event Publisher (Background poller)
    │ 5. Transmits record with headers: X-Correlation-ID=corr-test-trace-12345, traceparent=...
    │    "Outbox event published successfully: eventId=7d2dcbc1-..., eventType=ORDER_CREATED, topic=order.created, orderId=1001, attempt=1, durationMs=5, outcome=PUBLISHED"
    │    Metric incremented: events.outbox.published{outcome="published"} = 1.0
    ▼
Kafka Broker (Topic: order.created)
    ▼
Order Event Consumer (8083)
    │ 6. OrderEventConsumer consumes record:
    │    "Consumer event received: eventId=7d2dcbc1-..., eventType=ORDER_CREATED, orderId=1001, topic=order.created, partition=0, offset=100, outcome=RECEIVED"
    │    ProcessedEvent recorded in PostgreSQL processed_events table.
    │    "Consumer event processed successfully: eventId=7d2dcbc1-..., eventType=ORDER_CREATED, orderId=1001, durationMs=17, outcome=PROCESSED"
    │    Metric incremented: events.consumer.processed{outcome="success"} = 1.0
```

### 4.2 Injected Failure 1: Downstream Saga Compensation Recovery
- **Failure Injected:** Injected downstream inventory reservation failure during `createOrder`.
- **Observed Behavior:**
  - Order transitioned to `COMPENSATION_PENDING`.
  - Structured log output:
    `WARN ... Order state transition: orderId=2001, previousStatus=PENDING, newStatus=COMPENSATION_PENDING, outcome=SAGA_COMPENSATION_PENDING`
  - Gauge `saga.compensation.pending` reflected `1.0`.
- **Recovery:**
  - `SagaCompensationRecoveryService` executed:
    `INFO ... Found 1 orders in COMPENSATION_PENDING requiring Saga compensation recovery, outcome=SAGA_RECOVERY_STARTED`
    `INFO ... Successfully recovered Saga for orderId=2001: transitioned to CANCELLED with ORDER_CANCELLED event, outcome=SAGA_RECOVERY_SUCCESS`
  - Order transitioned to `CANCELLED`, `ORDER_CANCELLED` event was persisted in outbox, and metric `saga.compensation.total{outcome="recovered"}` incremented to `1.0`.

### 4.3 Injected Failure 2: Poison Message Dead-Letter Routing
- **Failure Injected:** Submitted event with invalid `orderId = null`.
- **Observed Behavior:**
  - Validation failed: `IllegalArgumentException: orderId must be a positive number`.
  - Structured log output:
    `ERROR ... Consumer event validation failed: eventId=evt-poison-1, orderId=null, error=orderId must be a positive number, outcome=DLT_ROUTED`
  - Metric `events.consumer.dlt{reason="validation_error"}` incremented to `1.0`.
  - Metric `events.consumer.processed{outcome="dlt_routed"}` incremented to `1.0`.
- **Replay Verification:**
  - Corrected event replayed via `DltReplayService`:
    `INFO ... DLT replay succeeded: eventId=evt-poison-1, orderId=1003, targetTopic=order.created, outcome=REPLAY_SUCCESS`
  - Metric `events.dlt.replay{outcome="success"}` incremented to `1.0`.

### 4.4 Injected Failure 3: Duplicate Replay Idempotency
- **Failure Injected:** Replayed already-processed event `evt-dup-101`.
- **Observed Behavior:**
  - `processedEventRepository.existsByEventId("evt-dup-101")` returned `true`.
  - Structured log output:
    `WARN ... Duplicate event skipped: eventId=evt-dup-101, eventType=OrderCreated, orderId=1002, durationMs=65, outcome=SKIPPED_DUPLICATE`
  - Metric `events.consumer.processed{outcome="duplicate"}` incremented to `1.0`.
  - Zero duplicate database writes occurred.

---

## 5. Verification Commands

To run all focused observability and operational tests:
```bash
mvn test -pl order-service "-Dtest=EventMetricsTest,ProductServiceHealthIndicatorTest,EventOperationsIntegrationTest"
```

To run all tests across all reactor modules:
```bash
mvn clean verify -DskipTests=false
```

To check for whitespace issues:
```bash
git diff --check
```
