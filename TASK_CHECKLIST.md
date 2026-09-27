# Epic 8 — Microservices Task Checklist

Source checklist: 21-Sep-2026 to 25-Sep-2026.

## Day 35 — Decomposition
- [x] User Service boundary
- [x] Product Service boundary
- [x] Order Service boundary
- [x] Database-per-service design
- [x] Architecture diagram
- [x] Service responsibilities
- [x] API list
- [x] Communication flow
- [x] Authentication flow

## Day 36 — Independent services
- [x] User Service :8081
- [x] Product Service :8082
- [x] Order Service :8083
- [x] Independent application.properties
- [x] user_db / product_db / order_db
- [x] Independent Maven modules

## Day 37 — OpenFeign
- [x] ProductClient
- [x] Order -> Product HTTP call
- [x] Product existence validation
- [x] Current price and stock validation
- [x] Stock update through Product Service
- [x] Product not found handling
- [x] Service failure handling
- [x] Connect/read timeout configuration

## Day 38 — Gateway & discovery
- [x] API Gateway :8080
- [x] Gateway routing
- [x] Eureka Discovery Service :8761
- [x] Logical service names / lb:// routes
- [x] Gateway JWT validation
- [x] Identity forwarding headers

## Day 39 — Resilience & final integration
- [x] Controlled 503 for downstream failure
- [x] Controlled 404 for invalid product
- [x] Timeout protection
- [x] No raw stack traces in service exception handlers
- [x] Authentication at gateway
- [x] Authorization inside services
- [x] Unit tests
- [x] Postman collection
- [x] README / RUN documentation
- [x] Architecture documentation

## Important architecture rule
Order Service has no ProductRepository and no UserRepository. It stores IDs/snapshots and communicates with Product Service over HTTP. This prevents cross-service database access.
