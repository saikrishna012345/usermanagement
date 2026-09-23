# 21–23 September Completion Checklist

## Microservices Architecture & Service Decomposition
- [x] Monolith vs microservices documented
- [x] Service boundaries identified
- [x] Database-per-service documented
- [x] Responsibilities documented
- [x] Architecture diagram
- [x] API list
- [x] Database ownership
- [x] Communication flow
- [x] Authentication flow concept

## Independent Spring Boot Microservices
- [x] user-service
- [x] product-service
- [x] order-service
- [x] UserController / UserService / UserRepository / User / DTO
- [x] ProductController / ProductService / ProductRepository / Product / DTO
- [x] OrderController / OrderService / OrderRepository / Order / OrderItem
- [x] user_db / product_db / order_db
- [x] Ports 8081 / 8082 / 8083
- [x] Independent application.properties
- [x] No cross-service database access

## OpenFeign
- [x] ProductClient
- [x] Synchronous HTTP/REST/JSON communication
- [x] Order → Product lookup
- [x] Current price validation
- [x] Availability validation
- [x] Product-not-found handling
- [x] Service-down / 5xx handling
- [x] Connection failure handling
- [x] Feign connect/read timeouts
- [x] Controlled API errors
- [x] No raw stack traces
