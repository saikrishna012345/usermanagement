# Microservices Architecture Design Document

## 1. Service boundaries

The existing monolithic backend is decomposed into bounded services:

### Authentication Service — planned boundary
- Registration
- Login
- JWT generation
- Refresh tokens

It is identified during decomposition but is not created in the 21–23 implementation scope.

### User Service
- User profile
- User CRUD
- User address/profile ownership boundary

For the 22-Sep implementation, the required User APIs are the core user CRUD endpoints.

### Product Service
- Products
- Categories
- Inventory
- Current product price and stock availability

### Order Service
- Orders
- Order items
- Order status
- Order creation using product information obtained from Product Service

### Notification Service — planned boundary
Notification responsibilities remain a future independent boundary; the 21–23 implementation does not create this service.

## 2. Database ownership

```text
User Service     -> user_db
Product Service  -> product_db
Order Service    -> order_db
```

Each service owns its database. A service must not query another service's database directly.

For example:

```text
WRONG:
Order Service -> JDBC -> product_db

CORRECT:
Order Service -> HTTP/OpenFeign -> Product Service -> product_db
```

## 3. API list

### User Service :8081

```text
GET  /api/users/{id}
POST /api/users
PUT  /api/users/{id}
```

### Product Service :8082

```text
GET  /api/products
GET  /api/products/{id}
POST /api/products
GET  /api/products/categories
POST /api/products/categories
```

### Order Service :8083

```text
POST /api/orders
GET  /api/orders/{id}
```

## 4. Communication flow

The communication required in this phase is synchronous HTTP/REST using OpenFeign:

```text
Mobile Client
     |
     v
Order Service :8083
     |
     | OpenFeign
     v
Product Service :8082
     |
     v
product_db
```

HTTP uses JSON request/response bodies. The Order Service uses a timeout so a slow Product Service does not block the request indefinitely.

## 5. Order creation flow

1. Order Service receives `productId` and `quantity`.
2. Order Service calls Product Service through `ProductClient`.
3. Product Service checks its own database.
4. Product Service returns the current product name, price and stock quantity.
5. Order Service verifies that requested quantity is available.
6. Order Service stores the order and order-item snapshot in `order_db`. Inventory mutation is intentionally left to the Product Service boundary; this phase only requires validation/lookup during order creation.

The order item stores the product ID, product name and unit price snapshot. It does not create a JPA relationship to a Product entity because Product belongs to another service.

## 6. Failure scenarios

| Scenario | Order Service behavior |
|---|---|
| Product exists | Continue order creation |
| Product does not exist | Return 404 `PRODUCT_NOT_FOUND` |
| Product Service returns 5xx | Return 503 `PRODUCT_SERVICE_UNAVAILABLE` |
| Connection failure | Return 503 `PRODUCT_SERVICE_UNAVAILABLE` |
| Feign read timeout | Return 504 `PRODUCT_SERVICE_TIMEOUT` |
| Quantity exceeds stock | Return 409 `INSUFFICIENT_STOCK` |

## 7. Authentication flow — architecture concept

The authentication boundary is identified during Day 35 decomposition:

```text
Mobile App
   |
   | credentials
   v
Authentication Service (planned)
   |
   | JWT access token
   v
Mobile App
   |
   | Bearer JWT
   v
Future API Gateway
   |
   v
User / Product / Order Services
```

Authentication and gateway enforcement are intentionally not implemented here because they are part of the following day's API Gateway work. Authorization rules can remain inside the individual services.

## 8. Why Order Service cannot query product_db

Product data and inventory are owned by Product Service. Direct database access would couple Order Service to Product Service's schema and make independent deployment difficult. The correct boundary is HTTP/OpenFeign communication.
