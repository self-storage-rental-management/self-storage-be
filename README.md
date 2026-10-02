# StorageHub Backend

Backend cho hệ thống quản lý kho tự lưu trữ StorageHub, xây dựng bằng Spring Boot, Spring Security, JWT, Spring Data JPA và MySQL.

## Trạng thái hiện tại

Đã có nền tảng:

- Đăng ký, đăng nhập, xác minh email, refresh token, đổi mật khẩu và đăng xuất.
- JWT authentication và phân quyền theo role/permission.
- Admin API cho users, roles, facilities, settings, login history, sessions và activity logs.
- Một số API nền tảng cho facilities, unit types, storage units, files và payments.

Đang phát triển:

- Customer business flow đầy đủ: hồ sơ, reservation, hợp đồng, bàn giao, trả kho và thanh toán theo nghiệp vụ.
- Các API trên có thể chưa đủ contract để kết nối toàn bộ Customer FE.

## Yêu cầu môi trường

- Java 21
- Docker Desktop
- MySQL 8.x (khuyến nghị dùng Docker)
- Git

Kiểm tra Java:

```cmd
java -version
```

## Quick start trên Windows

### 1. Clone đúng branch

```cmd
git clone -b develop https://github.com/self-storage-rental-management/self-storage-be.git
cd self-storage-be
```

Nếu đã clone repository:

```cmd
git switch develop
git pull --ff-only origin develop
```

### 2. Khởi động MySQL

Lần đầu tạo container:

```cmd
docker run --name storagehub-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=storagehub_local -p 3306:3306 -d mysql:8.4
```

Nếu container đã tồn tại nhưng đang dừng:

```cmd
docker start storagehub-mysql
```

Kiểm tra container:

```cmd
docker ps
```

### 3. Cấu hình biến môi trường

Các biến dưới đây áp dụng cho cửa sổ CMD hiện tại. Có thể dùng giá trị local khác nếu máy hoặc database khác cấu hình mặc định.

```cmd
set "STORAGEHUB_DB_URL=jdbc:mysql://localhost:3306/storagehub_local?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
set "STORAGEHUB_DB_USERNAME=root"
set "STORAGEHUB_DB_PASSWORD=root"
set "STORAGEHUB_ALLOWED_ORIGIN=http://localhost:5173"
set "STORAGEHUB_MAIL_ENABLED=false"
set "STORAGEHUB_EXPOSE_DEVELOPMENT_CODE=true"
```

Tạo JWT secret riêng cho máy local. Secret phải là Base64 và có tối thiểu 32 bytes:

```cmd
powershell -NoProfile -Command "$bytes=New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($bytes); [Convert]::ToBase64String($bytes)"
```

Copy kết quả rồi đặt vào biến:

```cmd
set "STORAGEHUB_JWT_SECRET=PASTE_BASE64_SECRET_HERE"
```

`STORAGEHUB_EXPOSE_DEVELOPMENT_CODE=true` chỉ dùng local để hiển thị mã xác minh khi chưa cấu hình mail. Không dùng cấu hình này ở production.

### 4. Chạy backend

```cmd
mvnw.cmd spring-boot:run
```

Backend mặc định chạy tại:

```text
http://localhost:8080
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

Trong Swagger UI, chọn `Authorize` và nhập JWT để gọi các API cần đăng nhập.

Dừng server bằng `Ctrl + C`.

## Các lệnh Maven thường dùng

| Lệnh | Mục đích |
| --- | --- |
| `mvnw.cmd spring-boot:run` | Chạy backend local |
| `mvnw.cmd test` | Chạy test |
| `mvnw.cmd clean package` | Build file JAR |

## API chính hiện có

### Auth

Base path: `/api/auth`

- `POST /register`
- `POST /login`
- `POST /refresh`
- `POST /verify-email`
- `POST /forgot-password`
- `POST /reset-password`
- `POST /logout`
- `POST /password`
- `GET /me`

### Admin

- `/api/admin/users`
- `/api/admin/roles`
- `/api/admin/settings`
- `/api/admin/activity-logs`
- `/api/admin/login-history`
- `/api/admin/sessions`

Các endpoint Admin yêu cầu JWT hợp lệ và permission tương ứng. Không tin role hoặc permission do FE tự gửi lên; backend phải kiểm tra từ JWT và dữ liệu server.

### Facility và tài nguyên nền tảng

- `GET /api/facilities`
- `GET /api/facilities/{facilityId}/unit-types`
- `GET /api/storage-units`
- `POST /api/files`
- `GET /api/files/{fileId}` (chỉ uploader hoặc Manager có quyền/phạm vi phù hợp)
- `POST /api/customer/reservations/{reservationId}/simulated-payment`
- `GET /api/customer/reservations/{reservationId}/payment`
- `POST /api/customer/reservations/{reservationId}/payment-complaints`
- `GET /api/customer/reservations/{reservationId}/payment-complaint`
- `POST /api/customer/payment-complaints/{complaintId}/withdraw`
- `/api/manager/payment-complaints`
- `GET /api/manager/payment-complaints/review-queue`
- `POST /api/customer/reservations/{reservationId}/booking-document`
- `GET /api/customer/reservations/{reservationId}/booking-document`
- `GET /api/customer/reservations/{reservationId}/booking-document/download`

Contract của các API nghiệp vụ Customer vẫn cần được hoàn thiện trước khi nối toàn bộ Customer FE.

## Cấu trúc thư mục

```text
src/main/java/com/storagehub/
├── api/          # Controller và request/response DTO
├── common/       # API response, exception và context dùng chung
├── config/       # Cấu hình ứng dụng
├── domain/       # Entity, enum và domain model
├── repository/   # Spring Data repositories
├── security/     # JWT, authentication và authorization
└── service/      # Business services
```

## Quy tắc làm việc với Git

Không code trực tiếp trên `develop`:

```cmd
git switch develop
git pull --ff-only origin develop
git switch -c feature/ten-tinh-nang
```

Sau khi hoàn thành:

```cmd
git add .
git commit -m "feat: mô tả ngắn thay đổi"
git push -u origin feature/ten-tinh-nang
```

Tạo Pull Request về `develop`. Trước khi push, kiểm tra không đưa password, JWT secret, token hoặc file cấu hình local vào Git.

## Lưu ý quan trọng

- `spring.jpa.hibernate.ddl-auto=update` phù hợp cho local development; cần migration rõ ràng trước production.
- Không commit secret thật hoặc thông tin database cá nhân.
- Nếu đổi port FE, cập nhật `STORAGEHUB_ALLOWED_ORIGIN`.
- Nếu đổi port hoặc host MySQL, cập nhật `STORAGEHUB_DB_URL`.
- Sau khi thay đổi biến môi trường, khởi động lại backend.
