# Tài liệu Thay đổi API & Checklist Triển khai Email Giao dịch (StorageHub)

Tài liệu này ghi nhận các thay đổi hợp đồng API (API Contracts), cấu trúc trang Frontend cần có, cơ chế quản lý Database / Migration / Backfill, và checklist triển khai email giao dịch dành cho đội ngũ Frontend và DevOps/Backend của StorageHub.

---

## 1. Thay đổi Hợp đồng API (API Contract Changes)

### 1.1. Admin tạo tài khoản người dùng (`POST /api/admin/users`)

- **URL**: `/api/admin/users`
- **Method**: `POST`
- **Quyền**: Yêu cầu quyền quản trị viên (`MANAGE_USERS`).
- **Thay đổi đối với trường `password`**:
  - Trường `password` trong body hiện là **optional** (không bắt buộc).
  - **Trường hợp 1 (Để trống/null/khoảng trắng)**: Backend tự động sinh mật khẩu tạm thời an toàn gồm **12 ký tự** ngẫu nhiên (chứa chữ hoa, chữ thường, số, ký tự đặc biệt). Sau đó, hệ thống đặt `mustChangePassword = true` và tự động gửi email thông báo tạo tài khoản kèm mật khẩu tạm thời đến địa chỉ email của người dùng mới bằng mẫu email `account-created`.
  - **Trường hợp 2 (Nhập thủ công)**: Nếu Admin nhập mật khẩu, mật khẩu phải tuân thủ chính sách `PasswordPolicy` (8–128 ký tự, bao gồm chữ hoa, chữ thường, chữ số, ký tự đặc biệt). Hệ thống vẫn đặt `mustChangePassword = true` và gửi mật khẩu này qua email cho người dùng.
- **Dữ liệu trả về (Response 200 OK)**:
  - `data.mustChangePassword`: `true`
  - Response **không trả về mật khẩu thô** nhằm đảm bảo an toàn thông tin.

---

### 1.2. Admin đặt lại mật khẩu cho tài khoản (`POST /api/admin/users/{id}/password-reset`)

- **URL**: `/api/admin/users/{id}/password-reset`
- **Method**: `POST`
- **Quyền**: Yêu cầu quyền quản trị viên (`MANAGE_USERS`).
- **Thay đổi đối với trường `temporaryPassword`**:
  - Trường `temporaryPassword` hiện là **optional** (không bắt buộc).
  - **Body mẫu**:
    ```json
    {
      "temporaryPassword": null
    }
    ```
    *(hoặc gửi `{}` rỗng).*
  - **Hành vi**:
    - Khi để trống hoặc `null`: Backend tự động sinh mật khẩu tạm thời an toàn **12 ký tự ngẫu nhiên** qua `SecureRandom`.
    - Sau khi reset: Đặt `mustChangePassword = true`, thu hồi toàn bộ session đăng nhập cũ của người dùng (`revokeSessions`), và gửi mật khẩu tạm thời trực tiếp vào hộp thư người dùng bằng mẫu email `account-created` (chủ đề: *"Tài khoản StorageHub của bạn đã được tạo"* hoặc biến thể đặt lại mật khẩu).
    - **Chỉ gửi đúng 1 email** duy nhất cho hành động này (không gửi lặp lại email `account-changed`).
- **Dữ liệu trả về (Response 200 OK)**:
  - `data.password`: `null`
  - `data.temporaryPassword`: `null` (không trả về cho Admin để chống nhìn lén).
  - `data.mustChangePassword`: `true`.
  - Mật khẩu chỉ được chuyển giao trực tiếp cho chủ sở hữu tài khoản qua kênh email cá nhân.

---

### 1.3. Tính bảo mật của `debugCode` trong API xác thực

- **Các API áp dụng**:
  - `POST /api/auth/register` (trả về trong `RegisterResponse`)
  - `POST /api/auth/forgot-password` (trả về trong `AuthChallengeResponse`)
- **Quy tắc bảo mật**:
  - `debugCode` **chỉ có giá trị** khi server chạy với cấu hình `app.auth.expose-development-code=true` **VÀ** nằm trong profile `dev` hoặc `local`.
  - Trên môi trường **Staging, Production**, trường `debugCode` sẽ **luôn luôn trả về `null`**, bất kể tham số cấu hình có được đặt như thế nào.
  - **Lưu ý cho Frontend**: Frontend **không được phụ thuộc** vào `debugCode` để tự động điền mã OTP hay token trên môi trường production; người dùng phải mở email để lấy token/OTP thật.

---

## 2. Frontend Cần Có (Các Trang & Route mà Link trong Email Dẫn Tới)

Email hệ thống chứa các đường link nút bấm (Call-To-Action) điều hướng người dùng trực tiếp về ứng dụng web. Dưới đây là danh sách các màn hình và tuyến đường (routes) Frontend cần xây dựng và cấu hình tương ứng ở Backend:

| # | Chức năng Frontend | Route khuyến nghị | Biến cấu hình Backend | Giá trị mặc định Dev | Mô tả chi tiết |
| :-: | :--- | :--- | :--- | :--- | :--- |
| **1** | **Xác minh Email** | `/verify-email` | `app.auth.verification-url` | `http://localhost:8443/verify-email?token=` | - Người dùng click từ nút "Xác minh email" trong thư chào mừng.<br>- Frontend trích xuất query param `token` (vẫn tương thích link cũ `verifyEmail`).<br>- Frontend gửi request `POST /api/auth/verify-email` với body `{ "email": "...", "token": "..." }`.<br>- Hiển thị trạng thái thành công và chuyển hướng đến màn hình Đăng nhập. |
| **2** | **Đặt lại Mật khẩu** | `/reset-password` | `app.auth.password-reset-url` | `http://localhost:8443/reset-password?token=` | - Người dùng click từ nút "Đặt lại mật khẩu" trong thư khôi phục.<br>- Frontend trích xuất query param `token` (vẫn tương thích link cũ `resetPassword`).<br>- Hiển thị form nhập mật khẩu mới và xác nhận mật khẩu (kiểm tra độ mạnh theo `PasswordPolicy`).<br>- Gửi `POST /api/auth/reset-password` với body `{ "email": "...", "token": "...", "newPassword": "..." }`. |
| **3** | **Bảo mật Tài khoản** | `/profile/security` | `app.mail.security-url` | `http://localhost:8443/profile/security` | - Người dùng click từ email "Đăng nhập mới" (`new-login`) khi thấy thiết bị lạ.<br>- Nếu chưa đăng nhập, Frontend hiển thị màn hình đăng nhập rồi trả về tab bảo mật.<br>- Màn hình cung cấp danh sách phiên đăng nhập và đổi mật khẩu cho Customer. |
| **4** | **Xem & Quản lý Tài khoản** | `/profile` | `app.mail.account-url` | `http://localhost:8443/profile` | - Người dùng click từ email thông báo thay đổi thông tin tài khoản (`account-changed`).<br>- Nếu chưa đăng nhập, Frontend hiển thị màn hình đăng nhập rồi trả về hồ sơ.<br>- Hiển thị thông tin cá nhân hiện tại để người dùng kiểm tra đối chiếu. |
| **5** | **Đăng nhập** | `/login` | `app.mail.login-url` | `http://localhost:8443/login` | - Nút đăng nhập trong email thông báo tạo tài khoản bởi quản trị viên (`account-created`) hoặc email xác minh hoàn tất.<br>- Cho phép người dùng đăng nhập bằng email và mật khẩu tạm thời.<br>- Nếu cờ `mustChangePassword = true` trả về trong phản hồi đăng nhập, hiển thị màn hình bắt buộc đổi mật khẩu trước khi vào Dashboard. |

---

## 3. Quản lý Cơ sở dữ liệu, Migration & Backfill Fingerprint

### 3.1. Cảnh báo `ddl-auto=update` và Khuyến nghị Migration
- Trong cấu hình phát triển cục bộ (`application-dev.properties`), thuộc tính `spring.jpa.hibernate.ddl-auto=update` giúp tự động sinh và cập nhật cột mới (`device_fingerprint`).
- **CẢNH BÁO QUAN TRỌNG**: Trên môi trường **Staging và Production**, **TUYỆT ĐỐI KHÔNG** dùng `ddl-auto=update` vì:
  1. Có thể gây khóa bảng (exclusive table locks) làm gián đoạn hệ thống đang chạy.
  2. Không có khả năng rollback khi có sự cố.
  3. Không kiểm soát được versioning và lịch sử thay đổi schema cơ sở dữ liệu.
  4. Không tự động xử lý đổi kiểu dữ liệu hay index an toàn.
- Backend đã tích hợp **Flyway** cho profile `staging` và `prod`. Hai profile này đặt `spring.jpa.hibernate.ddl-auto=validate`; Hibernate chỉ kiểm tra schema và không tự sửa database.
- Migration hiện tại nằm tại `src/main/resources/db/migration/V2026100201__add_login_history_device_fingerprint.sql`. Script có kiểm tra `information_schema`, nên an toàn khi triển khai lên database cũ đã từng được Hibernate thêm cột/index.
- Profile local/dev vẫn giữ `ddl-auto=update` và tắt Flyway để không phá quy trình phát triển hiện tại.

### 3.2. Script SQL Migration (Dành cho Production DBA)
Trước khi deploy code mới lên production, hãy chạy script SQL sau:

```sql
-- 1. Thêm cột device_fingerprint (độ dài 64 ký tự, có thể null ban đầu)
ALTER TABLE login_history ADD COLUMN device_fingerprint VARCHAR(64) NULL;

-- 2. Đánh index tối ưu hóa cho truy vấn kiểm tra thiết bị khi đăng nhập
CREATE INDEX idx_login_history_user_fp ON login_history (user_id, success, device_fingerprint);
```

### 3.3. Cơ chế Backfill Fingerprint cho Dữ liệu Đăng nhập Cũ (Mục 1)
- **Vấn đề**: Các bản ghi đăng nhập thành công trong quá khứ (`login_history`) có `device_fingerprint = NULL`. Nếu không xử lý, ngay lần đăng nhập đầu tiên sau khi triển khai, người dùng hiện tại sẽ bị hệ thống nhận diện nhầm là "thiết bị mới" và gửi email cảnh báo không cần thiết (false positive).
- **Giải pháp xử lý 2 tầng (Dual-Layer Strategy)**:
  1. **Tầng 1 - Backfill tự động theo lô khi khởi động (`LoginHistoryBackfillService`)**:
     - Cài đặt thông qua Spring `ApplicationRunner` với cấu hình bật/tắt và kích thước lô:
       ```properties
       app.login-history.backfill-fingerprints=true
       app.login-history.backfill-batch-size=500
       ```
     - **Cơ chế xử lý theo lô**: Hệ thống xử lý theo từng lô (500 dòng/lần, phân trang sắp xếp theo `id ASC`), ghi log tiến độ cụ thể cho từng lô và tổng số dòng đã cập nhật, **không nạp toàn bộ bảng vào bộ nhớ** để đảm bảo an toàn tài nguyên. Quá trình là idempotent: chỉ cập nhật các dòng có `device_fingerprint IS NULL`.
     - **Lý do chọn cách này**: Đảm bảo thuật toán phân tích chuỗi `userAgent` thành fingerprint (`Hệ điều hành | Họ trình duyệt`, ví dụ `Windows PC|Chrome`, `Mac|Firefox`) sử dụng **chính xác 100% cùng một logic code Java (`UserAgentParser`)**, không bị sai lệch do sự khác biệt giữa các regex engine của các hệ quản trị CSDL (MySQL, PostgreSQL, H2).
  2. **Tầng 2 - Tự phục hồi tại thời điểm người dùng đăng nhập (Runtime Self-Healing Fallback)**:
     - Trong phương thức `AuthService.handleNewLoginNotice`: Nếu người dùng có các bản ghi lịch sử cũ mang `device_fingerprint = NULL`, backend sẽ duyệt và parse nhanh các `userAgent` cũ đó. Nếu trùng fingerprint với thiết bị đang đăng nhập, backend sẽ:
       - Xem thiết bị này là **thiết bị đã biết** -> **KHÔNG gửi email cảnh báo**.
       - Tự động gán và lưu `device_fingerprint` vào bản ghi cũ đó trong DB để các lần đăng nhập sau có thể truy vấn index trực tiếp.
     - Lớp bảo vệ này đảm bảo người dùng cũ **không bao giờ** bị gửi email sai ngay cả khi cấu hình startup runner bị tắt hoặc chưa chạy xong.

- **Script SQL Backfill tham khảo (Tùy chọn cho DBA)**:
  Nếu DBA muốn cập nhật hàng loạt bằng SQL trực tiếp trên DB (ví dụ MySQL 8.0+):
  ```sql
  -- Cập nhật cơ bản các mẫu phổ biến (tùy chọn trước khi bật server)
  UPDATE login_history
  SET device_fingerprint = CONCAT(
      CASE
          WHEN user_agent LIKE '%Windows%' THEN 'Windows PC'
          WHEN user_agent LIKE '%Macintosh%' OR user_agent LIKE '%Mac OS%' THEN 'Mac'
          WHEN user_agent LIKE '%iPhone%' THEN 'iPhone'
          WHEN user_agent LIKE '%iPad%' THEN 'iPad'
          WHEN user_agent LIKE '%Android%' THEN 'Android'
          WHEN user_agent LIKE '%Linux%' THEN 'Linux PC'
          ELSE 'Unknown Device'
      END,
      '|',
      CASE
          WHEN user_agent LIKE '%Edg/%' THEN 'Edge'
          WHEN user_agent LIKE '%Chrome/%' THEN 'Chrome'
          WHEN user_agent LIKE '%Firefox/%' THEN 'Firefox'
          WHEN user_agent LIKE '%Safari/%' AND user_agent NOT LIKE '%Chrome/%' THEN 'Safari'
          WHEN user_agent LIKE '%OPR/%' OR user_agent LIKE '%Opera%' THEN 'Opera'
          ELSE 'Unknown Browser'
      END
  )
  WHERE device_fingerprint IS NULL AND user_agent IS NOT NULL;
  ```

---

## 4. Checklist Triển khai Môi trường Production (DevOps / Backend)

Khi triển khai backend lên máy chủ thực tế (Staging / Production), hãy thực hiện kiểm tra các mục sau:

### 4.1. Biến môi trường SMTP & Mail Server
Cấu hình các biến môi trường trong file `.env` hoặc hệ thống quản lý container (Docker / Kubernetes):

```env
# Bật tính năng gửi email giao dịch
STORAGEHUB_MAIL_ENABLED=true

# Thông tin SMTP Server (ví dụ: Google Workspace, AWS SES, SendGrid, Mailgun, Brevo...)
STORAGEHUB_MAIL_HOST=smtp.sendgrid.net
STORAGEHUB_MAIL_PORT=587
STORAGEHUB_MAIL_USERNAME=apikey
STORAGEHUB_MAIL_PASSWORD=SG.your_api_key_here
STORAGEHUB_MAIL_SMTP_AUTH=true
STORAGEHUB_MAIL_SMTP_STARTTLS=true

# Thông tin địa chỉ gửi & thương hiệu
STORAGEHUB_MAIL_FROM="StorageHub <no-reply@storagehub.vn>"
STORAGEHUB_MAIL_SUPPORT="support@storagehub.vn"
STORAGEHUB_MAIL_COMPANY_ADDRESS="Tòa nhà StorageHub, Khu Công Nghệ Cao, TP. Thủ Đức, TP. Hồ Chí Minh"
STORAGEHUB_MAIL_LOGO_URL="https://cdn.storagehub.vn/email/storagehub-logo.png"

# URL Frontend thực tế trên Production
STORAGEHUB_FRONTEND_BASE_URL="https://storagehub.vn"
STORAGEHUB_LOGIN_URL="https://storagehub.vn/login"
STORAGEHUB_SECURITY_URL="https://storagehub.vn/profile/security"
STORAGEHUB_ACCOUNT_URL="https://storagehub.vn/profile"
STORAGEHUB_VERIFICATION_URL="https://storagehub.vn/verify-email?token="
STORAGEHUB_PASSWORD_RESET_URL="https://storagehub.vn/reset-password?token="

# Bảo mật & Rate Limiting & Backfill
STORAGEHUB_EXPOSE_DEVELOPMENT_CODE=false
STORAGEHUB_SECURITY_ALERT_COOLDOWN_HOURS=6
STORAGEHUB_BACKFILL_FINGERPRINTS=true
```

Khởi động bằng profile tương ứng: `SPRING_PROFILES_ACTIVE=staging` hoặc `SPRING_PROFILES_ACTIVE=prod`. Các profile này không có giá trị SMTP, database, domain hay logo mặc định; thiếu biến môi trường bắt buộc sẽ khiến ứng dụng dừng lúc khởi động thay vì âm thầm gửi sai cấu hình.

### 4.2. Logo Thương hiệu trong Email
- Trên Staging/Production, bắt buộc đặt `STORAGEHUB_MAIL_LOGO_URL` thành URL HTTPS công khai, ổn định và không yêu cầu đăng nhập, ví dụ `https://cdn.storagehub.vn/email/storagehub-logo.png`.
- URL phải trả trực tiếp nội dung ảnh với đúng `Content-Type`, có cache header dài hạn và không dùng link tạm thời có chữ ký hết hạn.
- File `src/main/resources/static/email/storagehub-logo.png` và CID `storagehubLogo` vẫn là fallback để tương thích các email client chặn ảnh ngoài; URL công khai là nguồn thương hiệu chính cho môi trường triển khai.

### 4.3. Cấu hình DNS Xác thực Tên miền (SPF, DKIM, DMARC)
Để email hệ thống không bị phân loại vào hòm thư Rác (Spam / Junk) hoặc bị máy chủ nhận (Gmail, Yahoo, Microsoft 365) từ chối:

1. **SPF Record (TXT)**:
   - Thêm bản ghi TXT tại root domain của bạn (ví dụ: `storagehub.vn`):
     ```
     v=spf1 include:sendgrid.net include:_spf.google.com ~all
     ```
     *(Thay thế bằng relay server thực tế của bạn).*

2. **DKIM Record (CNAME / TXT)**:
   - Tạo khóa ký số DKIM từ nhà cung cấp dịch vụ gửi mail (AWS SES, SendGrid, Mailgun) và trỏ CNAME theo hướng dẫn của họ (ví dụ: `s1._domainkey.storagehub.vn`).

3. **DMARC Record (TXT)**:
   - Tạo bản ghi TXT tại `_dmarc.storagehub.vn`:
     ```
     v=DMARC1; p=quarantine; rua=mailto:dmarc-reports@storagehub.vn; pct=100; sp=quarantine
     ```

4. **Kiểm tra độ tin cậy**:
   - Sử dụng các công cụ như [Mail-Tester](https://www.mail-tester.com/) hoặc Google Postmaster Tools để kiểm tra điểm tin cậy đạt 10/10 trước khi gửi thư hàng loạt cho khách hàng thật.
