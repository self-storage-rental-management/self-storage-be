# StorageHub Backend

Backend cho hệ thống quản lý kho tự lưu trữ StorageHub. Tài liệu này bám theo code baseline main.

## Công nghệ

- Java 21, Spring Boot 4.1.1, Spring Web MVC.
- Spring Data JPA, MySQL 8.x.
- Spring Security, OAuth2 Resource Server và JWT.
- Spring Boot Mail, Springdoc OpenAPI/Swagger UI.
- Docker Desktop cho MySQL local.

Kiến trúc xử lý:

```text
HTTP request
  -> Controller / DTO validation
  -> Service nghiệp vụ
  -> Domain model / Repository
  -> MySQL
```

Package chính:

| Package | Trách nhiệm |
| --- | --- |
| api | Controller và request/response DTO |
| common | Response, exception handler và context dùng chung |
| config | JWT, Google, mail, file, payment, CORS, bootstrap admin |
| domain | Entity, enum và Spring Data repositories |
| security | JWT, role/permission và actor context |
| service | Authentication, Admin, reservation, payment, notification |

## Chức năng hiện có trong main

- Authentication: register, email verification, login/password, Google login, refresh token, logout, đổi/reset password.
- Profile: GET/PUT actor hiện tại tại /api/auth/me.
- Admin: users, roles, facilities, settings, dashboard, login history, sessions và activity logs.
- Facility: facilities, unit types và storage units.
- File: multipart upload theo storage path cấu hình.
- Notification: xem, đọc một thông báo hoặc đọc tất cả.
- Reservation: compatibility check, quote, create, list, detail, cancel và email verification.
- Staff: review reservation và quyết định review.
- Payment: payment intent và payment webhook.

Google login xác minh issuer, thời gian sống và audience của Google ID token. Tài khoản Google mới được tạo với role Customer và trạng thái active.

## Yêu cầu

- Git
- JDK 21
- Docker Desktop với MySQL 8.x
- Windows dùng mvnw.cmd; Linux/macOS dùng ./mvnw

## Chạy local trên Windows

### 1. Lấy source

```cmd
git clone -b main https://github.com/self-storage-rental-management/self-storage-BE.git
cd self-storage-BE
```

Nếu repository đã tồn tại:

```cmd
git switch main
git pull --ff-only origin main
```

### 2. Chạy MySQL

```cmd
docker run --name storagehub-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=storagehub_local -p 3306:3306 -d mysql:8.4
```

Nếu container đã có sẵn:

```cmd
docker start storagehub-mysql
```

### 3. Cấu hình local

```cmd
set "STORAGEHUB_DB_URL=jdbc:mysql://localhost:3306/storagehub_local?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
set "STORAGEHUB_DB_USERNAME=root"
set "STORAGEHUB_DB_PASSWORD=root"
set "STORAGEHUB_ALLOWED_ORIGIN=http://localhost:5173,http://localhost:8443"
set "STORAGEHUB_GOOGLE_CLIENT_ID=YOUR_GOOGLE_CLIENT_ID"
set "STORAGEHUB_MAIL_ENABLED=false"
set "STORAGEHUB_EXPOSE_DEVELOPMENT_CODE=true"
set "STORAGEHUB_FILE_STORAGE_PATH=./uploads"
```

Tạo secret JWT Base64 tối thiểu 32 bytes:

```cmd
powershell -NoProfile -Command "$bytes=New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($bytes); [Convert]::ToBase64String($bytes)"
```

```cmd
set "STORAGEHUB_JWT_SECRET=PASTE_BASE64_SECRET_HERE"
```

Không commit database password, JWT secret, SMTP password, payment webhook secret hoặc bootstrap-admin password.

### 4. Email thật bằng Gmail (tuỳ chọn)

Dùng Gmail App Password, không dùng mật khẩu Gmail chính:

```powershell
$env:STORAGEHUB_MAIL_ENABLED="true"
$env:STORAGEHUB_MAIL_FROM="storagehub.sender@example.com"
$env:STORAGEHUB_MAIL_HOST="smtp.gmail.com"
$env:STORAGEHUB_MAIL_PORT="587"
$env:STORAGEHUB_MAIL_USERNAME="your-sender@gmail.com"
$env:STORAGEHUB_MAIL_PASSWORD="GMAIL_APP_PASSWORD"
$env:STORAGEHUB_MAIL_SMTP_AUTH="true"
$env:STORAGEHUB_MAIL_SMTP_STARTTLS="true"
$env:STORAGEHUB_EXPOSE_DEVELOPMENT_CODE="false"
```

Cập nhật callback nếu FE chạy port khác:

```powershell
$env:STORAGEHUB_VERIFICATION_URL="http://localhost:5173/?verifyEmail="
$env:STORAGEHUB_PASSWORD_RESET_URL="http://localhost:5173/?resetPassword="
```

### 5. Chạy backend

```cmd
mvnw.cmd spring-boot:run
```

Mặc định:

- Backend: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## Các biến cấu hình chính

| Biến | Mục đích | Mặc định |
| --- | --- | --- |
| STORAGEHUB_DB_URL | JDBC URL MySQL | storagehub_local |
| STORAGEHUB_JWT_SECRET | JWT secret Base64 | placeholder |
| STORAGEHUB_JWT_EXPIRATION | Access token, giây | 3600 |
| STORAGEHUB_JWT_REFRESH_EXPIRATION | Refresh token, giây | 604800 |
| STORAGEHUB_GOOGLE_CLIENT_ID | Google OAuth client ID | rỗng |
| STORAGEHUB_ALLOWED_ORIGIN | CORS origins | 5173,8443 |
| STORAGEHUB_PAYMENT_WEBHOOK_SECRET | Payment webhook secret | placeholder |
| STORAGEHUB_FILE_STORAGE_PATH | Thư mục upload | ./uploads |
| STORAGEHUB_FILE_MAX_SIZE_BYTES | File tối đa | 10485760 |
| STORAGEHUB_MAIL_ENABLED | Bật mail thật | false |
| STORAGEHUB_MAIL_HOST / PORT | SMTP server | rỗng / 587 |
| STORAGEHUB_MAIL_USERNAME / PASSWORD | SMTP credential | rỗng |
| STORAGEHUB_VERIFICATION_URL | URL verify email | localhost:5173 |
| STORAGEHUB_PASSWORD_RESET_URL | URL reset password | localhost:5173 |
| STORAGEHUB_EXPOSE_DEVELOPMENT_CODE | Hiện mã local | false |
| STORAGEHUB_BOOTSTRAP_ADMIN_* | Bootstrap admin | tắt/rỗng |

## API chính theo main

### Auth — /api/auth

- POST /register
- POST /login
- POST /google
- POST /refresh
- POST /verify-email
- POST /forgot-password
- POST /reset-password
- POST /logout
- POST /password
- GET /me
- PUT /me

### Admin

- Users: /api/admin/users và các route con cho detail, roles, facilities, status, unlock, password-reset.
- Roles: /api/admin/roles và /api/admin/roles/{role}/permissions.
- Settings: /api/admin/settings và /api/admin/settings/{key}.
- Security: /api/admin/dashboard, /api/admin/login-history, /api/admin/sessions và revoke session.
- Activity: /api/admin/activity-logs.

### Facility, file, notification

- GET /api/facilities
- GET /api/facilities/{facilityId}/unit-types
- GET /api/storage-units
- POST /api/files
- GET /api/notifications
- PATCH /api/notifications/{id}/read
- PATCH /api/notifications/read-all

### Reservation, staff và payment

- POST /api/customer/reservations/compatibility-check
- POST /api/customer/reservations/quote
- POST /api/customer/reservations
- GET /api/customer/reservations
- GET /api/customer/reservations/{reservationId}
- POST /api/customer/reservations/{reservationId}/cancel
- POST /api/customer/reservations/{reservationId}/email-verification
- POST /api/customer/reservations/{reservationId}/email-verification/resend
- POST /api/customer/reservations/{reservationId}/payment-intent
- GET /api/staff/reservation-reviews
- POST /api/staff/reservation-reviews/{reservationId}/decision
- POST /api/payments/intents
- POST /api/webhooks/payments/{provider}

Contract chi tiết: docs/BOOKING_API_CONTRACT.md và thư mục docs/.

## Lệnh Maven

```cmd
mvnw.cmd spring-boot:run
mvnw.cmd test
mvnw.cmd clean package
```

## Git và production

- Authorization phải được kiểm tra ở backend từ JWT/security context; không tin role do FE gửi.
- spring.jpa.hibernate.ddl-auto=update phù hợp local/development; production cần migration có kiểm soát.
- CORS production chỉ cho phép domain FE thật.
- Tắt STORAGEHUB_EXPOSE_DEVELOPMENT_CODE ở production.
- Không commit secret hoặc thông tin credential.
- Không bật stacktrace/message nội bộ trong response production.


