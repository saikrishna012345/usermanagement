# Mobile Commerce Backend — Microservices

This project is the microservices phase of the existing Mobile Commerce Backend. It implements only the requirements for **21-Sep-2026, 22-Sep-2026 and 23-Sep-2026**.

## Included in this phase

- Microservices architecture and service decomposition
- User Service
- Product Service
- Order Service
- Independent PostgreSQL databases
- Order → Product synchronous communication using Spring Cloud OpenFeign
- Product-not-found, service-down, connection-failure and timeout handling
- No cross-service database access
- Architecture/design documentation

## Services

| Service | Port | Database | Responsibility |
|---|---:|---|---|
| User Service | 8081 | user_db | Users, registration/user CRUD |
| Product Service | 8082 | product_db | Products, categories, inventory |
| Order Service | 8083 | order_db | Orders and order items |

The API Gateway and service discovery are intentionally **not implemented in this phase**; they belong to the 24-Sep task. Authentication/gateway flow is documented as an architecture concept, not implemented as a gateway in this phase.

## Architecture

```text
                         MOBILE APP
                              |
                              v
                    +-------------------+
                    |  Future API       |
                    |  Gateway :8080    |
                    +-------------------+
                       |      |      |
                       v      v      v
                    USER    PRODUCT   ORDER
                   :8081     :8082    :8083
                     |         |        |
                     v         v        v
                  user_db  product_db order_db
                              ^
                              |
                         OpenFeign / HTTP
                              |
                         Order Service
```

For this phase, clients may call the three services directly on their assigned ports. The gateway shown above is the **future architecture boundary** documented for the next sprint day.

## PostgreSQL setup

The supplied `docker-compose.yml` starts PostgreSQL and creates three databases:

- `user_db`
- `product_db`
- `order_db`

Default local credentials are `postgres` / `postgres`.

```bash
docker compose up -d
```

## Run each service independently

### User Service

```bash
cd user-service
mvn spring-boot:run
```

Runs on `http://localhost:8081`.

### Product Service

```bash
cd product-service
mvn spring-boot:run
```

Runs on `http://localhost:8082`.

### Order Service

Start Product Service first, then:

```bash
cd order-service
mvn spring-boot:run
```

Runs on `http://localhost:8083`.

## Main APIs

### User Service

```text
GET  /api/users/{id}
POST /api/users
PUT  /api/users/{id}
```

### Product Service

```text
GET  /api/products
GET  /api/products/{id}
POST /api/products
GET  /api/products/categories
POST /api/products/categories
```

### Order Service

```text
POST /api/orders
GET  /api/orders/{id}
```

## Order → Product flow

```text
POST /api/orders
       |
       v
Order Service
       |
       | OpenFeign / HTTP
       v
Product Service
       |
       v
Product Database
       |
       v
ProductResponse (price + stock)
       |
       v
Validate product + quantity
       |
       v
Create Order in order_db
```

The Order Service never connects to `product_db`. It obtains product information over HTTP through `ProductClient`.

## Postman

Import `postman_collection.json` to test the three services and the Order → Product OpenFeign flow.

## Failure handling

The Order Service converts downstream failures into controlled API responses:

- Product not found → `404 PRODUCT_NOT_FOUND`
- Product Service unavailable/5xx → `503 PRODUCT_SERVICE_UNAVAILABLE`
- Connection failure → `503 PRODUCT_SERVICE_UNAVAILABLE`
- Timeout → `504 PRODUCT_SERVICE_TIMEOUT`
- Invalid request/quantity → `400`

Raw stack traces are not returned to the API client.

## Technology

- Java 17
- Spring Boot 3.3.2
- Spring Cloud OpenFeign / Spring Cloud 2023.0.x
- Spring Data JPA / Hibernate
- PostgreSQL
- Maven
- JUnit 5 / Mockito

## Scope note

This repository intentionally stops after the 23-Sep-2026 requirements. API Gateway, Eureka/service discovery implementation and gateway authentication enforcement are reserved for the 24-Sep-2026 task.
