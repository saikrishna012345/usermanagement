# Mobile Commerce Microservices Architecture

## Architecture

```text
Mobile App
   |
   v
API Gateway :8080
   |
   +--> User Service :8081 ----> user_db
   |
   +--> Product Service :8082 -> product_db
   |
   +--> Order Service :8083 ----> order_db
                 |
                 | OpenFeign / HTTP
                 v
           Product Service

Service Registry: Eureka :8761
```

## Responsibilities
- User Service: registration, login/JWT, user CRUD, profile/address data.
- Product Service: products, categories, inventory.
- Order Service: orders/order items/order status; it stores product snapshot fields and never queries product_db.
- API Gateway: single entry point, routing, JWT validation, forwarded identity headers.
- Eureka: service registration/discovery and logical service names.

## Database ownership
Each service owns only its database. No service contains a repository for another service's database.

## Communication
Order Service calls Product Service using OpenFeign. The order flow is: receive product ID -> fetch product -> validate product/price/stock -> reserve/decrease stock -> create order.

## Authentication flow
1. Client logs in through Gateway.
2. Gateway routes login to User Service.
3. User Service authenticates and returns JWT.
4. Client sends JWT to Gateway for protected APIs.
5. Gateway validates signature/expiry and forwards identity headers.
6. Individual services also validate JWT for defense in depth and apply method-level authorization.

## Failure handling
- Product not found: controlled 404.
- Product service unavailable/connection failure: controlled 503.
- Timeout: Feign connect/read timeout prevents indefinite waiting.
- Invalid request: controlled 400.
- No raw stack traces are returned.
