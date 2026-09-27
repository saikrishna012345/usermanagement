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

## Verification
- `GET /api/v1/users/{id}` through Gateway
- `GET /api/v1/products/{id}` through Gateway
- `POST /api/v1/orders` through Gateway
- Stop Product Service and repeat order creation to verify controlled downstream failure.
- Send an expired/invalid JWT to a protected endpoint to verify Gateway authentication.
