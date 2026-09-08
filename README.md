# Mobile Backend – User & Authentication Foundation

A Spring Boot REST API for user management and basic authentication, built on a layered
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
├── controller     REST endpoints (UserController, AuthController)
├── service        Business logic (UserService, AuthService)
├── repository     Spring Data JPA repositories
├── model          JPA entities (User)
├── dto            Request/response DTOs, ApiResponse wrapper
├── exception      Custom exceptions + GlobalExceptionHandler
└── MobileBackendApplication.java
```

## Prerequisites

- JDK 17+
- Maven (or use IntelliJ's bundled Maven support)
- PostgreSQL running locally (e.g. via pgAdmin 4), with a database named `mobile_backend`

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
    - In IntelliJ: open `MobileBackendApplication.java` and click the green Run icon, or
    - From terminal: `mvn spring-boot:run`
5. The API is available at `http://localhost:8080`

Hibernate automatically creates the `users` table on first run (`spring.jpa.hibernate.ddl-auto=update`).

## Running Tests

Run all unit tests for the service layer:
- In IntelliJ: right-click `src/test/java` → **Run 'Tests in usermanagement'**
- From terminal: `mvn test`

Tests use Mockito to mock the repository layer, so no database connection is required to run them.

## API Response Format

All endpoints return a consistent JSON envelope.

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
  "message": "User not found",
  "errorCode": "USER_NOT_FOUND"
}
```

See `API_DOCUMENTATION.md` for the full endpoint reference, and import
`postman_collection.json` into Postman to try the API directly.