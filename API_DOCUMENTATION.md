# API Documentation — Gateway

Base URL: `http://localhost:8080`

## Authentication
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

## User Service
- `GET /api/v1/users/{id}`
- `POST /api/v1/users`
- `PUT /api/v1/users/{id}`
- `GET /api/v1/users`

## Product Service
- `GET /api/v1/products`
- `GET /api/v1/products/{id}`
- `POST /api/v1/products`
- `PUT /api/v1/products/{id}`
- `PATCH /api/v1/products/{id}/stock` — service-to-service inventory update
- `GET /api/v1/categories`
- `POST /api/v1/categories`

## Order Service
- `POST /api/v1/orders`
- `GET /api/v1/orders/{id}`
- `GET /api/v1/orders/my`
- `PUT /api/v1/orders/{id}/cancel`
- `GET /api/v1/orders/user/{userId}` — admin

## Failure scenarios
- Invalid product: HTTP 404.
- Product service unavailable: HTTP 503 from controlled business error handling.
- Feign connect/read timeout: bounded by 2s/3s configuration.
- Invalid request: HTTP 400.
- Missing/invalid JWT at gateway: HTTP 401.
