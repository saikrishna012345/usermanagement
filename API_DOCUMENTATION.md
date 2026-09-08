# API Documentation

Base URL: `http://localhost:8080`

All responses are wrapped in a standard envelope:

```json
{ "success": true, "message": "...", "data": { } }
```
or, on error:
```json
{ "success": false, "message": "...", "errorCode": "..." }
```

---

## User Endpoints

### Create User
`POST /api/users`

**Request body**
```json
{
  "firstName": "Sai",
  "lastName": "Krishna",
  "email": "sai@example.com",
  "mobileNumber": "9876543210",
  "password": "secret123",
  "status": "ACTIVE"
}
```

**Validation rules**

| Field | Rule |
|---|---|
| firstName | required, not blank |
| lastName | required, not blank |
| email | required, valid email format |
| mobileNumber | required, exactly 10 digits |
| password | required, not blank |
| status | optional |

**Responses**

| Status | Condition |
|---|---|
| `201 Created` | User created successfully |
| `400 Bad Request` | Validation failed (`errorCode: VALIDATION_ERROR`) |
| `409 Conflict` | Email or mobile number already registered (`errorCode: DUPLICATE_USER`) |

---

### Get All Users (paginated)
`GET /api/users?page=0&size=10&sort=createdAt,desc`

| Param | Default | Description |
|---|---|---|
| page | 0 | Zero-indexed page number |
| size | 10 | Items per page |
| sort | — | `field,direction` e.g. `firstName,asc` |

**Response** `200 OK`
```json
{
  "success": true,
  "message": "Users fetched successfully",
  "data": {
    "content": [ ],
    "page": 0,
    "size": 10,
    "totalElements": 0,
    "totalPages": 0,
    "last": true
  }
}
```

---

### Get User by ID
`GET /api/users/{id}`

| Status | Condition |
|---|---|
| `200 OK` | User found |
| `404 Not Found` | No user with that id (`errorCode: USER_NOT_FOUND`) |

---

### Update User
`PUT /api/users/{id}`

Request body: same shape as Create User.

| Status | Condition |
|---|---|
| `200 OK` | Updated successfully |
| `400 Bad Request` | Validation failed |
| `404 Not Found` | User does not exist |

---

### Delete User
`DELETE /api/users/{id}`

| Status | Condition |
|---|---|
| `204 No Content` | Deleted successfully (no response body) |
| `404 Not Found` | User does not exist |

---

### Search Users
`GET /api/users/search?name=ajay`

Matches `firstName` or `lastName` containing the given value (case-insensitive).

**Response** `200 OK`
```json
{
  "success": true,
  "message": "Users fetched successfully",
  "data": [ ]
}
```

---

## Authentication

### Login
`POST /api/auth/login`

**Request body**
```json
{
  "email": "sai@example.com",
  "password": "secret123"
}
```

**Responses**

| Status | Condition |
|---|---|
| `200 OK` | Login successful |
| `401 Unauthorized` | Invalid email or password (`errorCode: INVALID_CREDENTIALS`) |
| `400 Bad Request` | Missing/malformed email or password |

**Success response**
```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "userId": 1,
    "firstName": "Sai",
    "lastName": "Krishna",
    "email": "sai@example.com"
  }
}
```

Note: this is the basic authentication flow only — no token/session is issued yet.
JWT/Spring Security is a separate, later module.

---

## Error Codes Reference

| errorCode | HTTP Status | Meaning |
|---|---|---|
| VALIDATION_ERROR | 400 | Request body failed `@Valid` constraints |
| INVALID_USER | 400 | Business-level invalid user data |
| USER_NOT_FOUND | 404 | No user exists with the given id |
| DUPLICATE_USER | 409 | Email or mobile number already registered |
| INVALID_CREDENTIALS | 401 | Login email/password mismatch |
| INTERNAL_SERVER_ERROR | 500 | Unexpected server error |