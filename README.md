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
