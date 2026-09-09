# Mobile Commerce Backend

A Spring Boot REST API for a mobile application backend, covering user management,
authentication, and a product/order commerce module — built on a layered
Controller → Service → Repository → Spring Data JPA → PostgreSQL architecture.

## Tech Stack

- Java 17
- Spring Boot 3.3.2 (Web, Validation, Data JPA)
- PostgreSQL
- Maven
- JUnit 5 + Mockito + AssertJ (unit tests)

## Package Structure

```
com.company.mobilebackend
├── controller     REST endpoints
├── service        Business logic
├── repository     Spring Data JPA repositories
├── model          JPA entities
├── dto            Request/response DTOs, ApiResponse wrapper
├── exception      Custom exceptions + GlobalExceptionHandler
└── MobileBackendApplication.java
```

## Entities & Relationships

```
User
 ├── Profile   (One-to-One)
 ├── Address   (One-to-Many)
 └── Order     (One-to-Many)
      └── OrderItem  (One-to-Many)
           └── Product  (Many-to-One)

Product
 └── Category  (Many-to-One)
```

## Modules

| Module | Endpoints |
|---|---|
| User | `POST/GET /api/users`, `GET/PUT/DELETE /api/users/{id}`, `GET /api/users/search` |
| Auth | `POST /api/auth/login` (basic email+password check, no JWT yet) |
| Profile | `POST/GET /api/users/{id}/profile` |
| Address | `POST/GET /api/users/{id}/addresses`, `DELETE /api/addresses/{id}` |
| Category | `POST/GET /api/categories`, `GET /api/categories/{id}/products` |
| Product | `POST/GET /api/products`, `GET/PUT/DELETE /api/products/{id}`, `GET /api/products/search` |
| Order | `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/users/{id}/orders`, `PUT /api/orders/{id}/cancel` |

`GET /api/products` supports combined filtering, pagination, and sorting:
`?category={id}&minPrice=100&maxPrice=5000&page=0&size=10&sort=price,asc`

## Prerequisites

- JDK 17+
- Maven (or use IntelliJ's bundled Maven support)
- PostgreSQL running locally, with a database named `mobile_backend`

## Setup

1. Clone the repository:
   ```
   git clone https://github.com/saikrishna012345/usermanagement.git
   ```
2. Create the PostgreSQL database:
   ```sql
   CREATE DATABASE mobile_backend;
   ```
3. Update `src/main/resources/application.properties` with your local PostgreSQL credentials:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/mobile_backend
   spring.datasource.username=postgres
   spring.datasource.password=your_password
   ```
4. Run the application:
   - In IntelliJ: open the main application class and click the green Run icon, or
   - From terminal: `mvn spring-boot:run`
5. The API is available at `http://localhost:8080`

Hibernate automatically creates all tables on first run (`spring.jpa.hibernate.ddl-auto=update`).

## Running Tests

- In IntelliJ: right-click `src/test/java` → **Run 'Tests in usermanagement'**
- From terminal: `mvn test`

Unit tests cover `UserService`, `AuthService`, `ProductService`, and `OrderService`
(including insufficient-stock and cancel/stock-restore scenarios), using Mockito to mock
the repository layer — no live database connection required.

## Order Creation Flow (Transactional)

```
Validate User → Validate Products → Check Inventory
   → Create Order → Create Order Items → Update Inventory → Commit
```

The entire flow runs inside a single `@Transactional` method. If any step fails
(e.g. insufficient stock), all database changes made earlier in that same request
are rolled back automatically.

## API Response Format

Success:
```json
{
  "success": true,
  "message": "User created successfully",
  "data": { }
}
```

Error:
```json
{
  "success": false,
  "status": 404,
  "message": "User not found",
  "errorCode": "USER_NOT_FOUND",
  "timestamp": "2026-08-27T10:30:00"
}
```

See `API_DOCUMENTATION.md` for the full endpoint reference, and import
`postman_collection_phase2.json` into Postman for the complete request set,
including negative and validation test cases.   