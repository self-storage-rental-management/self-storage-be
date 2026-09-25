# Authentication API contract

Base URL: `/api`

## Roles

- `ADMIN`
- `CUSTOMER`
- `STAFF`
- `FACILITY_MANAGER`
- `OPERATIONS_MANAGER`

Enum values are uppercase in the database and API. Public registration always creates an `ACTIVE` `CUSTOMER`; clients cannot choose a privileged role.

## Register

`POST /api/auth/register`

Request:

```json
{
  "fullName": "Nguyen Van A",
  "email": "user@example.com",
  "phone": "0901234567",
  "password": "Password@123"
}
```

Success: `201 Created`

```json
{
  "data": {
    "id": 15,
    "fullName": "Nguyen Van A",
    "email": "user@example.com",
    "phone": "0901234567",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-09-18T10:30:00Z"
  }
}
```

Duplicate email: `409 Conflict`

```json
{
  "error": {
    "code": "EMAIL_ALREADY_EXISTS",
    "message": "Email is already registered",
    "details": {}
  }
}
```

Invalid request: `400 Bad Request`

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": {
      "email": "Email format is invalid"
    }
  }
}
```

Passwords and password hashes are never returned by the API.
