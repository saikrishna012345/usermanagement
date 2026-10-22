# Mobile Commerce Backend - Microservices

This repository is the microservices implementation of the Mobile Commerce Backend sprint.

## Services
| Service | Port | Database |
|---|---:|---|
| Discovery Service | 8761 | - |
| API Gateway | 8080 | - |
| User Service | 8081 | user_db |
| Product Service | 8082 | product_db |
| Order Service | 8083 | order_db |

## Run
1. Start PostgreSQL: `docker compose up -d`
2. Start Eureka: `cd discovery-service && mvn spring-boot:run`
3. Start User/Product/Order services.
4. Start Gateway last.
5. Use the Postman collection with Gateway base URL `http://localhost:8080`.

## Required order
For order creation, Product Service must be registered/running because Order Service calls it through OpenFeign.

## Verification & Integration Testing
- Detailed testing guide, event schemas, Saga flows, and integration verification checklist are documented in [INTEGRATION_TESTING_GUIDE.md](file:///INTEGRATION_TESTING_GUIDE.md).
- Comprehensive Event Contracts, Kafka Topic Topology, Outbox & Idempotency boundaries, and Sequence Diagrams are documented in [docs/event-driven-architecture.md](file:///docs/event-driven-architecture.md).
- `GET /api/v1/users/{id}` through Gateway
- `GET /api/v1/products/{id}` through Gateway
- `POST /api/v1/orders` through Gateway
- Stop Product Service and repeat order creation to verify controlled downstream failure.
- Send an expired/invalid JWT to a protected endpoint to verify Gateway authentication.
- Full reactor test suite: `mvn clean verify`

## Event Contracts & Kafka Topics
- **Canonical Envelope:** `EventEnvelope<T>` (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `aggregateId`, `payload`).
- **Core Topics:**
  - `order.created`, `order.cancelled`, `order.shipped`, `order.delivered` (partition key: `orderId`).
  - `inventory.updated` (partition key: `productId`).
  - `notification.requested` (partition key: `recipientUserId`).
  - Dead-letter topics with suffix `.dlt` (e.g. `order.created.dlt`, `order.cancelled.dlt`, `order.shipped.dlt`, `order.delivered.dlt`).
- **Producer Reliability:** Configured with `enable.idempotence = true`, `acks = all`, `retries = 3`, and `max.in.flight.requests.per.connection = 5`.
- **Transaction Guarantee:** Local PostgreSQL ACID transaction inserts into `orders` and `outbox_events` atomically; background `OutboxEventPublisher` delivers at-least-once to Kafka with exponential backoff on broker failure and optimistic locking concurrency coordination (`@Version`).
- **Consumer Deduplication:** Consumers enforce idempotency by recording and checking `eventId` in `processed_events`.
- **Run Event Tests:** `mvn test -Dtest=EventEnvelopeContractTest,OrderCreatedEventContractTest,OrderEventConsumerTest,OutboxEventPublisherTest,EventIntegrationAndRecoveryTest,DltReplayServiceTest,CorrelationIdUtilsTest,CorrelationIdFilterTest,CorrelationIdProducerInterceptorTest` inside `order-service`.

## Observability, Metrics & Event Operations
- **Comprehensive Runbooks & Event Operations:** Full operational documentation, incident runbooks, and failure-injection trace evidence are documented in [docs/event-operations.md](file:///docs/event-operations.md).
- **Correlation ID Propagation & Sanitization:**
  - `X-Correlation-ID` header validated and sanitized against `^[A-Za-z0-9_-]{1,64}$` with secure UUID fallback at API Gateway (`CorrelationIdGlobalFilter`).
  - Populated in SLF4J MDC (`correlationId`) across all services (`CorrelationIdFilter`).
  - Forwarded on all outbound OpenFeign requests (`CorrelationIdFeignInterceptor`).
  - Persisted inside outbox event payloads and attached to Kafka message headers via `CorrelationIdProducerInterceptor` and `OutboxEventPublisher`.
  - Extracted and populated in consumer thread MDC (`OrderEventConsumer`), ensuring unbroken trace correlation across HTTP, database, and Kafka boundaries.
- **Structured Operational Logging:**
  - Standardized console pattern incorporating `traceId`, `spanId`, `correlationId`, and `orderId` in MDC.
  - Standard structured fields: `timestamp`, `level`, `service`, `correlationId`, `traceId`, `spanId`, `orderId`, `eventId`, `eventType`, `topic`, `partition`, `offset`, `attempt`, `durationMs`, `outcome`.
  - Standardized outcome catalog: `ORDER_CREATED`, `ORDER_CONFIRMED`, `ORDER_CANCELLED`, `ORDER_SHIPPED`, `ORDER_DELIVERED`, `OUTBOX_SAVED`, `PUBLISHED`, `RETRY_SCHEDULED`, `FAILED`, `RESERVED`, `ALREADY_RESERVED`, `RELEASED`, `ALREADY_RELEASED`, `COMMITTED`, `ALREADY_COMMITTED`, `INSUFFICIENT_STOCK`, `RECEIVED`, `PROCESSED`, `SKIPPED_DUPLICATE`, `RETRY_ATTEMPT`, `DLT_ROUTED`, `REPLAY_ATTEMPTED`, `REPLAY_SUCCESS`, `REPLAY_FAILED`, `SAGA_COMPENSATION_TRIGGERED`, `SAGA_COMPENSATION_COMPLETED`, `SAGA_COMPENSATION_PENDING`, `SAGA_RECOVERY_STARTED`, `SAGA_RECOVERY_SUCCESS`.
  - Secret redaction: Zero passwords, JWTs, card numbers, or authorization headers logged. Error messages scrubbed via `OutboxEventPublisher.sanitizeErrorMessage()`.
- **Operational Metrics (Micrometer & Prometheus):**
  - **Kafka Native Consumer Lag:** `kafka.consumer.fetch.manager.records.lag.max` (max lag) and `kafka.consumer.fetch.manager.records.lag` (per-partition lag) registered via `MicrometerConsumerListener`.
  - **Outbox State Gauges:** `events.outbox.pending` (backlog awaiting publish) and `events.outbox.failed` (terminal failures).
  - **Outbox Counters & Timers:** `events.outbox.published` (`topic`, `event_type`, `outcome`), `events.outbox.duration` (latency timer).
  - **Consumer Counters & Timers:** `events.consumer.processed` (`topic`, `event_type`, `outcome`), `events.consumer.duration`, `events.consumer.retries`, `events.consumer.dlt` (`topic`, `event_type`, `reason`).
  - **Saga Metrics:** `saga.compensation.pending` (gauge of orders awaiting recovery), `saga.compensation.total` (`outcome`).
  - **DLT Replay:** `events.dlt.replay` (`topic`, `outcome`).
  - **Downstream Dependency Health:** `ProductServiceHealthIndicator` reporting Circuit Breaker availability (`productService: UP/DOWN`).
  - **Bounded Cardinality:** Metric labels strictly limited to static strings (`topic`, `event_type`, `outcome`, `reason`). No unbounded IDs (e.g. `orderId`, `eventId`, `correlationId`) as tags.
- **Actuator & Prometheus Endpoints:**
  - `GET /actuator/health` (public component health status).
  - `GET /actuator/prometheus` (Prometheus scrape endpoint, RBAC-secured).
  - `GET /actuator/metrics` (Micrometer metrics catalog, RBAC-secured).
  - `POST /api/v1/orders/admin/dlt/replay` (Admin endpoint for controlled DLT replay).
- **Prometheus Scrape & Alert Configuration:**
  - Scrape configurations for microservices defined in [monitoring/prometheus/prometheus.yml](file:///monitoring/prometheus/prometheus.yml).
  - 9 production-ready alert rules in [monitoring/prometheus/alerts.yml](file:///monitoring/prometheus/alerts.yml):
    - `KafkaConsumerLagGrowth` (>100 records for 2m)
    - `ConsumerLagCritical` (>500 records for 1m)
    - `PoisonMessagesDetected` (increase in DLT events)
    - `ConsumerFailureRateHigh` (>5% error rate for 2m)
    - `OutboxBacklogGrowing` (>50 pending events for 3m)
    - `OutboxTerminalFailuresDetected` (events marked FAILED)
    - `SagaCompensationsPending` (pending compensation > 5m)
    - `DownstreamProductServiceUnavailable` (Product Service circuit breaker OPEN)
    - `KafkaBrokerUnavailable` (Kafka health DOWN)
- **Incident Runbooks Summary:**
  - **Poison Messages:** Inspect DLT topics (`*.dlt`) with non-secret payload logging, diagnose root cause, fix upstream or deploy patch, replay via `POST /api/v1/orders/admin/dlt/replay`.
  - **Consumer Lag Growth:** Monitor `kafka.consumer.fetch.manager.records.lag.max`, check partition distribution and consumer thread pool, scale consumer instances horizontally if processing capacity is bottlenecked.
  - **Kafka Broker Outages:** Transactional outbox retains events in PostgreSQL (`PENDING`) with exponential backoff; automatically drains queue when broker recovers without data loss or message duplication.
  - **Downstream Product Service Outages:** Resilience4j circuit breaker fast-fails, orders isolate in `COMPENSATION_PENDING`, periodic `SagaCompensationRecoveryService` heals inconsistent state upon service restoration.
- **Event Operations Test Suite:**
  - `mvn test -Dtest=EventMetricsTest,ProductServiceHealthIndicatorTest,EventOperationsIntegrationTest -pl order-service`


## Distributed Tracing with OpenTelemetry
- **W3C Trace Context Propagation:** Standard `traceparent` headers (`00-{traceId}-{spanId}-{flags}`) validated, generated when missing/malformed, and propagated across API Gateway (8080) -> Order Service (8083) -> Product Service (8082) via OpenFeign, and into Kafka message records for `order.created`.
- **Distinct from Correlation IDs:** `traceId` tracks distributed latency and spans; `X-Correlation-ID` groups business request logs. Both coexist in MDC (`[traceId=%X{traceId:-} spanId=%X{spanId:-} corr=%X{correlationId:-} order=%X{orderId:-}]`).
- **Local Trace Backend (Jaeger):**
  - **Start Jaeger:** `docker compose up -d jaeger`
  - **Jaeger Web UI:** `http://localhost:16686`
  - **OTLP HTTP Endpoint:** `http://localhost:4318/v1/traces` (container DNS: `http://jaeger:4318/v1/traces`)
- **Tracing Tests:** `mvn test -Dtest=TraceContextUtilsTest,TraceContextFilterTest,TraceContextGlobalFilterTest,CorrelationIdFeignInterceptorTest,CorrelationIdProducerInterceptorTest,DistributedTracingTest`

## Resilience Testing, Chaos Recovery & Saga Orchestration
- **OpenFeign Failures & Timeouts:** Connect timeout (2s) and read timeout (5s) enforce bounded calls. `ProductClientFallbackFactory` maps downstream 404 to `ResourceNotFoundException`, 400/409 to `BusinessException`, and 503/timeouts/circuit breaker rejections to `ProductServiceUnavailableException` without ever fabricating false success or pretending stock was reserved.
- **Circuit Breaker Transitions & Bounded Retries:** Resilience4j circuit breaker on `product-service` triggers `OPEN` state after $\ge 50\%$ failure rate over 10 calls, fast-rejecting with `CallNotPermittedException` for 10s, transitioning to `HALF_OPEN` for 2 probe calls, and automatically recovering to `CLOSED`. Bounded retries limit execution to 3 attempts.
- **Transactional Outbox Recovery:** Kafka outages cause events to remain `PENDING` with exponential backoff ($\min(60, 2^{\text{attempts}})$). When the broker recovers, the publisher resumes and transitions records to `PUBLISHED`. Terminal failures after 5 attempts transition to `FAILED` with sanitized error details (`password=***`, `token=***`).
- **Consumer Idempotency & DLT Routing:** `OrderEventConsumer` enforces deduplication via `processed_events`. Duplicate events are skipped (`SKIPPED_DUPLICATE`). Fatal non-retryable exceptions (`IllegalArgumentException`, `DeserializationException`) route immediately to `order.created.dlt`. Transient failures retry 3 times with `FixedBackOff(2000L, 3L)`. `DltReplayService` enables safe replay preserving partition keys and correlation IDs.
- **Saga Consistency, Compensation & Recovery:**
  - Order workflow: `PENDING -> Stock Reserved -> Payment Authorized -> Stock Committed -> CONFIRMED`.
  - When reservation or payment fails, compensating release calls run on all reserved items. If all succeed, the order transitions to `CANCELLED` (emitting `ORDER_CANCELLED` to Outbox).
  - If downstream compensation fails/times out, the order transitions to `COMPENSATION_PENDING` (no cancellation event emitted), isolating the order.
  - Across application restarts, `SagaCompensationRecoveryService` polls `COMPENSATION_PENDING` orders, retries `releaseReservation`, and upon success transitions the order to `CANCELLED` and writes the `ORDER_CANCELLED` outbox event.
  - Defends against late confirmation events and guarantees repeated compensation idempotency.
- **Resilience & Saga Test Suite:**
  - `mvn test -Dtest=OpenFeignResilienceTest,KafkaOutboxResilienceTest,ConsumerResilienceTest,SagaCompensationResilienceTest,SagaRecoveryAndStateTransitionTest,ResilienceObservabilityTest -pl order-service`
  - `mvn test -Dtest=ProductReservationChaosTest -pl product-service`

## Security Hardening & API Access Control
- **JWT Cryptographic Verification & Fail-Closed Validation:** Configured with HMAC-SHA256 (`app.jwt.secret`) across API Gateway and microservices. Rejects missing, malformed, expired, tampered, or algorithm-manipulated tokens with HTTP 401 Unauthorized (`ApiResponse` standard schema). Missing required claims (`sub`, `role`) or invalid numeric claims fail closed without throwing unhandled HTTP 500 exceptions.
- **Gateway Identity Sanitization & Anti-Spoofing:** `JwtGatewayFilter` strips client-supplied `X-User-Email`, `X-User-Role`, and `X-User-Id` headers from every incoming request, eliminating header spoofing vulnerabilities on both public and protected routes. Sanitized headers are populated only after cryptographically verifying the JWT signature.
- **Role-Based Access Control (RBAC):** Consistent role normalization (`ADMIN`, `ROLE_ADMIN` -> `ROLE_ADMIN`; `USER`, `ROLE_USER` -> `ROLE_USER`). Non-admin users cannot access administrative endpoints (`/actuator/**`, `/api/v1/users`, `/api/v1/users/admin`, `/api/v1/orders/{id}/ship`, `/api/v1/orders/{id}/deliver`).
- **Resource Ownership & Anti-Privilege Escalation:** `UserSecurity` enforces that regular users can only read or update their own profile (`GET/PUT /api/v1/users/{id}` or `/me`). Non-admins are prevented from altering the `role` field.
- **Order State Machine Protection:** Administrative shipping (`PATCH /api/v1/orders/{id}/ship`) and delivery (`PATCH /api/v1/orders/{id}/deliver`) strictly enforce both `ROLE_ADMIN` authority and order lifecycle preconditions (`CONFIRMED -> SHIPPED` and `SHIPPED -> DELIVERED`). Invalid transitions are rejected with `BusinessException` (HTTP 409/400).
- **CORS, CSRF & HTTP Headers:** CSRF is safely disabled for stateless Bearer token authentication. CORS is hardened with `allowCredentials(false)` to prevent wildcard credential leakage, alongside Spring Security HTTP headers (`nosniff`, `DENY`).
- **Comprehensive Documentation:** Detailed security specification and endpoint access matrix documented in [docs/security.md](file:///docs/security.md).
- **Security Test Suites:**
  - `mvn test -Dtest=JwtGatewaySecurityTest -pl gateway-service`
  - `mvn test -Dtest=JwtValidationAndRbacSecurityTest -pl user-service`
  - `mvn test -Dtest=ProductSecurityTest -pl product-service`
  - `mvn test -Dtest=OrderSecurityAndAccessControlTest -pl order-service`
