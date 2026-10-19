# Security Architecture and Access Control Specification

## 1. Executive Summary
This document specifies the security controls, authentication mechanisms, authorization policies, secret handling practices, and threat remediations implemented across the Mobile Commerce Microservices backend.

---

## 2. Authentication & JWT Validation Architecture

### 2.1 Token Format and Cryptographic Verification
- **Signing Algorithm:** HMAC-SHA256 (`HmacSHA256`) using configured secret `app.jwt.secret`.
- **Key Derivation:** Cryptographic secret keys are derived strictly using `io.jsonwebtoken.security.Keys.hmacShaKeyFor` with minimum 256 bits of entropy.
- **Fail-Closed Validation:** Every microservice (`gateway-service`, `user-service`, `product-service`, `order-service`) verifies the JWT signature independently.
- **Clock Skew and Expiration:** Expiration time (`exp`) is enforced. Expired tokens are rejected with HTTP 401 Unauthorized (`ApiResponse` standard format) without exposing internal stack traces or database errors.

### 2.2 Claim Validation and Safe Parsing
- **Subject (`sub`):** Must be non-null and non-blank (representing user email). Missing subject claims cause immediate rejection.
- **Role (`role`):** Must be non-null, non-blank, and match recognized roles (`USER`, `ADMIN`). Normalized to `ROLE_ADMIN` or `ROLE_USER`. Unknown or missing roles fail closed.
- **User Identifier (`userId`):** Safely deserialized handling `Number` (`Integer` or `Long`) and numeric strings to prevent Jackson `RequiredTypeException` or casting crashes.
- **Token Protection in Logs:** Raw JWT tokens, user passwords, and signing keys are strictly excluded from logs and error payloads.

---

## 3. API Gateway Security & Header Sanitization

### 3.1 Anti-Spoofing and Identity Forwarding
- **Client Header Stripping:** Any untrusted external client sending forwarded identity headers (`X-User-Email`, `X-User-Role`, `X-User-Id`) has these headers stripped immediately in `JwtGatewayFilter` before route evaluation.
- **Downstream Identity Propagation:** Only when a valid, cryptographically verified JWT is processed are sanitized headers (`X-User-Email`, `X-User-Role`, `X-User-Id`) populated for downstream consumption.
- **Public Routes:** Requests to public endpoints (`/api/v1/auth/**`, `/api/v1/health`, GET `/api/v1/products/**`) have any incoming identity headers stripped to guarantee downstream services do not receive forged user identities.

---

## 4. Role-Based Access Control (RBAC) & Endpoint Matrix

| Method | Endpoint | Access Level | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | User registration |
| `POST` | `/api/v1/auth/login` | Public | User authentication and JWT issuance |
| `GET` | `/api/v1/health` | Public | Service liveness probe |
| `GET` | `/actuator/health` | Public | Actuator health endpoint |
| `GET` | `/api/v1/products/**` | Public | Product browsing |
| `GET` | `/api/v1/categories/**` | Public | Category browsing |
| `GET` | `/api/v1/users/me` | Authenticated | Current user profile |
| `PUT` | `/api/v1/users/me` | Authenticated | Update current user profile |
| `GET` | `/api/v1/users/{id}` | Self or ADMIN | View user by ID (enforced by `UserSecurity`) |
| `PUT` | `/api/v1/users/{id}` | Self or ADMIN | Update user (role escalation prohibited) |
| `GET` | `/api/v1/users` | ADMIN Only | List all users with pagination |
| `GET` | `/api/v1/users/admin` | ADMIN Only | Admin user listing |
| `DELETE`| `/api/v1/users/{id}` | ADMIN Only | Delete user |
| `POST` | `/api/v1/orders` | Authenticated | Create order (initiates Saga) |
| `GET` | `/api/v1/orders/my` | Authenticated | Retrieve caller's order history |
| `GET` | `/api/v1/orders/{id}` | Authenticated | Retrieve order details |
| `PUT` | `/api/v1/orders/{id}/cancel` | Authenticated | Cancel pending order |
| `GET` | `/api/v1/orders/user/{userId}`| ADMIN Only | List orders for specific user |
| `PATCH`| `/api/v1/orders/{id}/ship` | ADMIN Only | Transition order: `CONFIRMED -> SHIPPED` |
| `PATCH`| `/api/v1/orders/{id}/deliver` | ADMIN Only | Transition order: `SHIPPED -> DELIVERED` |
| `POST` | `/api/v1/products/{id}/reservations` | Authenticated | Inventory reservation (inter-service) |
| `PATCH`| `/api/v1/products/{id}/stock` | Authenticated | Inventory modification (inter-service) |
| `*` | `/actuator/**` (except `/health`) | ADMIN Only | Actuator management and metrics |

---

## 5. Order State Machine & Business Transition Protection
Administrative shipping and delivery operations strictly enforce both authorization and state-machine preconditions:
- **Shipping (`PATCH /api/v1/orders/{id}/ship`):**
  - Requires `ROLE_ADMIN`.
  - State precondition: `order.status == CONFIRMED`. Transition to `SHIPPED`.
  - Attempting to ship from `PENDING`, `CANCELLED`, or `COMPENSATION_PENDING` throws `BusinessException` (HTTP 409/400).
- **Delivery (`PATCH /api/v1/orders/{id}/deliver`):**
  - Requires `ROLE_ADMIN`.
  - State precondition: `order.status == SHIPPED`. Transition to `DELIVERED`.
  - Attempting to deliver from `CONFIRMED` or `PENDING` throws `BusinessException` (HTTP 409/400).

---

## 6. CORS, CSRF, and HTTP Security Headers
- **CSRF:** Explicitly disabled across all microservices because authentication is completely stateless using standard HTTP `Authorization: Bearer <token>` headers. The application does not issue or read session cookies or automatically attach credentials from browsers.
- **CORS:** Credentialed wildcard origins are strictly prohibited (`allowCredentials(false)`). Allowed methods (`GET, POST, PUT, DELETE, PATCH, OPTIONS`) and headers (`Authorization, Content-Type, X-Correlation-ID, Accept, Origin`) are explicitly configured.
- **Security Headers:** Spring Security defaults are active:
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`

---

## 7. Secrets Management & Configuration
- **No Hardcoded Production Secrets:** Default fallback keys in configuration files are development placeholders only.
- **Environment Ingestion:** Production deployments inject credentials through environment variables:
  - `APP_JWT_SECRET`
  - `USER_DB_PASSWORD`, `PRODUCT_DB_PASSWORD`, `ORDER_DB_PASSWORD`
  - `EUREKA_URL`, `KAFKA_BOOTSTRAP_SERVERS`
- **Actuator Endpoint Exposure:** Exposed endpoints are limited to `health,info`. Sensitive inspection endpoints (`env`, `beans`, `configprops`, `heapdump`) are strictly disabled from web exposure.

---

## 8. Service-to-Service Communication & Network Boundaries
- **Inter-Service Authentication:** OpenFeign calls (such as `Order Service -> Product Service` stock reservation) forward the caller's `Authorization: Bearer <token>` and `X-Correlation-ID` header through `FeignConfig.requestForwardingInterceptor()`.
- **Direct Access Defense:** In addition to the API Gateway, backend services (`product-service`, `order-service`) validate JWT signatures independently on all non-public routes.
- **Deployment Recommendation:** In production environments, backend service container ports (`8081`, `8082`, `8083`) should be bound solely to the internal Docker bridge / private VPC subnet, with only the API Gateway (`8080`) exposed to the public perimeter.

---

## 9. Automated Security Test Suites
To verify security hardening across modules:
```powershell
# Gateway security (JWT rejection, claims, spoofing prevention)
mvn test -Dtest=JwtGatewaySecurityTest -pl gateway-service

# User Service security (Numeric claims, role normalization, RBAC, UserSecurity)
mvn test -Dtest=JwtValidationAndRbacSecurityTest -pl user-service

# Product Service security (Token validation, role normalization, entry point)
mvn test -Dtest=ProductSecurityTest -pl product-service

# Order Service security (Ship/deliver RBAC, state validation preservation)
mvn test -Dtest=OrderSecurityAndAccessControlTest -pl order-service

# Full verification across all reactor modules
mvn clean verify -DskipTests=false
```
