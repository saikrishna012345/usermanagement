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
  - Dead-letter topics with suffix `.dlt` (e.g. `order.created.dlt`).
- **Transaction Guarantee:** Local PostgreSQL ACID transaction inserts into `orders` and `outbox_events` atomically; background `OutboxEventPublisher` delivers at-least-once to Kafka with exponential backoff on broker failure.
- **Consumer Deduplication:** Consumers enforce idempotency by recording and checking `eventId` in `processed_events`.
- **Run Event Tests:** `mvn test -Dtest=EventEnvelopeContractTest,OrderCreatedEventContractTest,OrderEventConsumerTest,OutboxEventPublisherTest,EventIntegrationAndRecoveryTest,DltReplayServiceTest,CorrelationIdUtilsTest,CorrelationIdFilterTest,CorrelationIdProducerInterceptorTest` inside `order-service`.

## Observability & Operational Monitoring
- **Correlation ID Propagation:** `X-Correlation-ID` header validated and sanitized (`^[A-Za-z0-9_-]{1,64}$` with secure UUID fallback) at Gateway, propagated to SLF4J MDC in all services, forwarded over OpenFeign inter-service calls, and attached to Kafka message headers via `CorrelationIdProducerInterceptor`.
- **Structured Operational Logs:** All event transitions (Outbox publish/retry/failure, Consumer receipt/dedup/success/DLT, DLT replay) output structured fields (`timestamp`, `level`, `service`, `correlationId`, `eventId`, `eventType`, `orderId`, `topic`, `partition`, `offset`, `attempt`, `durationMs`, `outcome`).
- **Health & Actuator Endpoints:** Actuator `/actuator/health` and `/actuator/info` enabled and secured across Gateway (8080), User Service (8081), Product Service (8082), Order Service (8083), and Config Server (8888).
- **Operational Runbook & Queries:** Schema-accurate PostgreSQL monitoring queries for `outbox_events` (`PENDING` retry queue and `FAILED` terminal errors) and `orders` (`COMPENSATION_PENDING`) documented in [docs/event-driven-architecture.md](file:///docs/event-driven-architecture.md).

## Distributed Tracing with OpenTelemetry
- **W3C Trace Context Propagation:** Standard `traceparent` headers (`00-{traceId}-{spanId}-{flags}`) validated, generated when missing/malformed, and propagated across API Gateway (8080) -> Order Service (8083) -> Product Service (8082) via OpenFeign, and into Kafka message records for `order.created`.
- **Distinct from Correlation IDs:** `traceId` tracks distributed latency and spans; `X-Correlation-ID` groups business request logs. Both coexist in MDC (`[traceId=%X{traceId:-} spanId=%X{spanId:-} corr=%X{correlationId:-} order=%X{orderId:-}]`).
- **Local Trace Backend (Jaeger):**
  - **Start Jaeger:** `docker compose up -d jaeger`
  - **Jaeger Web UI:** `http://localhost:16686`
  - **OTLP HTTP Endpoint:** `http://localhost:4318/v1/traces` (container DNS: `http://jaeger:4318/v1/traces`)
- **Tracing Tests:** `mvn test -Dtest=TraceContextUtilsTest,TraceContextFilterTest,TraceContextGlobalFilterTest,CorrelationIdFeignInterceptorTest,CorrelationIdProducerInterceptorTest,DistributedTracingTest`

## Resilience Testing & Chaos Recovery
- **OpenFeign Failures & Timeouts:** Connect timeout (2s) and read timeout (5s) enforce bounded calls. `ProductClientFallbackFactory` maps downstream 404 to `ResourceNotFoundException`, 400/409 to `BusinessException`, and 503/timeouts/circuit breaker rejections to `ProductServiceUnavailableException` without ever fabricating false success or pretending stock was reserved.
- **Circuit Breaker Transitions & Bounded Retries:** Resilience4j circuit breaker on `product-service` triggers `OPEN` state after $\ge 50\%$ failure rate over 10 calls, fast-rejecting with `CallNotPermittedException` for 10s, transitioning to `HALF_OPEN` for 2 probe calls, and automatically recovering to `CLOSED`. Bounded retries limit execution to 3 attempts.
- **Transactional Outbox Recovery:** Kafka outages cause events to remain `PENDING` with exponential backoff ($\min(60, 2^{\text{attempts}})$). When the broker recovers, the publisher resumes and transitions records to `PUBLISHED`. Terminal failures after 5 attempts transition to `FAILED` with sanitized error details (`password=***`, `token=***`).
- **Consumer Idempotency & DLT Routing:** `OrderEventConsumer` enforces deduplication via `processed_events`. Duplicate events are skipped (`SKIPPED_DUPLICATE`). Fatal non-retryable exceptions (`IllegalArgumentException`, `DeserializationException`) route immediately to `order.created.dlt`. Transient failures retry 3 times with `FixedBackOff(2000L, 3L)`. `DltReplayService` enables safe replay preserving partition keys and correlation IDs.
- **Saga Consistency & Compensation:** When reservation or payment fails, `OrderService` executes compensating release calls on all reserved items. If all releases succeed, the order transitions to `CANCELLED` and publishes `ORDER_CANCELLED`. If any release fails, the order transitions to `COMPENSATION_PENDING` (no cancellation event emitted), isolating the failure for operational investigation.
- **Resilience Test Suite:**
  - `mvn test -Dtest=OpenFeignResilienceTest,KafkaOutboxResilienceTest,ConsumerResilienceTest,SagaCompensationResilienceTest,ResilienceObservabilityTest -pl order-service`
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
