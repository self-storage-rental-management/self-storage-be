# Authentication API contract

Base URL: `/api`

## Roles

- `ADMIN`
- `CUSTOMER`
- `STAFF`
- `MANAGER`
- `BUSINESS`

Enum values are uppercase in the database and API. Public registration always creates a `PENDING_VERIFICATION` `CUSTOMER`; clients cannot choose a privileged role or facility scope.

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

Success: `200 OK`

```json
{
  "data": {
    "actor": {
      "id": "user-uuid",
      "fullName": "Nguyen Van A",
      "email": "user@example.com",
      "phone": "0901234567",
      "roles": ["CUSTOMER"],
      "status": "PENDING_VERIFICATION",
      "facilityScopes": {},
      "mustChangePassword": false,
      "permissions": []
    },
    "verificationRequired": true,
    "debugCode": null
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

`debugCode` is populated only when `app.auth.expose-development-code=true`; production deployments should leave it disabled.

## Verification and recovery

- `POST /api/auth/verify-email` accepts an email plus OTP, or only a link token. The challenge expires after 15 minutes, is single-use, and locks after five failed attempts.
- `POST /api/auth/refresh` rotates a refresh token and returns a new short-lived access JWT.
- `POST /api/auth/forgot-password` creates a one-time OTP/link without revealing whether an account exists.
- `POST /api/auth/reset-password` consumes the OTP/link, changes the password, and revokes existing sessions.

## Admin account management

All `/api/admin/users` endpoints require a verified JWT containing the corresponding permission. Permissions are evaluated from the server-issued JWT; request-body permissions are never trusted.

- `manage_users`: list, read, create, update profile fields, change status, reset password.
- `manage_roles`: assign roles and facility scopes.
- `view_audit_logs` and `manage_settings` are reserved for their respective admin modules.

Endpoints:

- `GET /api/admin/users?page=0&size=20&search=&role=&status=&facilityId=`
- `GET /api/admin/users/{id}`
- `POST /api/admin/users`
- `PATCH /api/admin/users/{id}`
- `PATCH /api/admin/users/{id}/roles`
- `PATCH /api/admin/users/{id}/facilities`
- `PATCH /api/admin/users/{id}/status`
- `POST /api/admin/users/{id}/password-reset`

Responses include `id`, `fullName`, `email`, `phone`, `status`, `roles`, `facilityScopes`, `mustChangePassword`, `createdAt`, and `updatedAt`. `passwordHash` and previous passwords are never returned. Account removal is represented by an inactive, suspended, locked, or disabled status so audit history remains intact.
