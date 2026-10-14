# STORAGEHUB - TÀI LIỆU CSDL & HƯỚNG DẪN XUẤT ERD THEO NGHIỆP VỤ & ROLE

Tài liệu này tổng hợp toàn bộ 49 bảng nghiệp vụ của hệ thống **StorageHub**, được phân rã thành các "cục" (module & role) độc lập, tự chứa (self-contained) với khóa ngoại chuẩn chỉ để bạn:
1. **Chụp hình ERD trực tiếp từ Mermaid** trong file này (dán thẳng vào SRS/Docs).
2. **Import từng file SQL vào DBeaver / MySQL Workbench** để render sơ đồ quan hệ ERD đồ họa độ nét cao và chụp ảnh/xuất PNG.

---

## I. DANH MỤC CÁC TỆP SQL PHÂN RÃ (ĐÃ ĐƯỢC GENERATE)

Tất cả các tệp SQL đã được kiểm tra cú pháp và toàn vẹn ràng buộc khóa ngoại (Foreign Keys) thành công 100% trên MySQL:

### 1. Phân rã theo Phân hệ Nghiệp vụ (`scripts/erd_modules/`)
| STT | File SQL | Tên Phân hệ Nghiệp vụ | Vai trò liên quan | Số bảng |
| :--- | :--- | :--- | :--- | :---: |
| 1 | [`module_1_auth_rbac.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_1_auth_rbac.sql) | Xác thực, Người dùng & Phân quyền RBAC | Admin, Toàn bộ Users | 9 |
| 2 | [`module_2_facilities_units.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_2_facilities_units.sql) | Quản lý Cơ sở, Loại kho & Ô kho lưu trữ | Manager, Business Ops | 4 |
| 3 | [`module_3_booking_checkin.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_3_booking_checkin.sql) | Báo giá, Đặt chỗ, Kiểm định hàng & Check-in | Customer, Staff, Manager | 10 |
| 4 | [`module_4_rentals_payments_renewals.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_4_rentals_payments_renewals.sql) | Hợp đồng, Thanh toán, Gia hạn & Trả kho / Xử lý nợ | Customer, Business, Staff | 15 |
| 5 | [`module_5_support_maintenance.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_5_support_maintenance.sql) | Hỗ trợ CSKH, Chuyển cấp & Bảo trì ô kho | Customer, Staff, Technician | 9 |
| 6 | [`module_6_system_assets_audit.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_modules/module_6_system_assets_audit.sql) | Cấu hình tham số, File tài sản & Nhật ký kiểm toán | Admin, System Audit | 4 |

### 2. Phân rã theo Vai trò Người dùng (`scripts/erd_roles/`)
| STT | File SQL | Tên Góc nhìn Vai trò (Role View) | Mục đích báo cáo Docs |
| :--- | :--- | :--- | :--- |
| 1 | [`role_1_customer.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_roles/role_1_customer.sql) | **Role: Customer** (Khách hàng) | Minh họa toàn bộ luồng trải nghiệm khách hàng: Tìm kho -> Đặt chỗ -> Hàng hóa -> Hợp đồng -> Thanh toán -> Gia hạn -> Trả kho -> Khiếu nại/Hỗ trợ. |
| 2 | [`role_2_staff_technician.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_roles/role_2_staff_technician.sql) | **Role: Staff & Technician** (Nhân viên & Kỹ thuật) | Minh họa quy trình tác nghiệp tại kho: Duyệt danh mục hàng hóa, gán ô kho, lập biên bản check-in, kiểm tra trả kho, tạo và hoàn thành phiếu bảo trì ô kho. |
| 3 | [`role_3_manager_business.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_roles/role_3_manager_business.sql) | **Role: Manager & Business** (Quản lý & Kinh doanh) | Minh họa quy trình điều hành: Quản lý biểu giá, chính sách khuyến mãi gói, duyệt gia hạn, đối soát hoàn tiền/khiếu nại, giám sát nợ quá hạn và thu hồi kho. |
| 4 | [`role_4_admin.sql`](file:///d:/SWP391/BE/self-storage-BE/scripts/erd_roles/role_4_admin.sql) | **Role: Admin** (Quản trị viên) | Minh họa phân hệ bảo mật & quản trị: Phân quyền vai trò RBAC, phạm vi chi nhánh (scope), phiên đăng nhập (sessions), thử thách OTP/2FA, cấu hình và nhật ký kiểm toán. |

---

## II. HƯỚNG DẪN IMPORT & CHỤP ẢNH ERD TRONG DBEAVER

Khi làm tài liệu phần mềm (Software Architecture Document / SRS):
1. Mở **DBeaver**.
2. Kết nối tới MySQL local (Database `storagehub_local`).
3. **Cách 1 - Xem ERD theo schema riêng**:
   - Tạo một schema tạm (ví dụ `erd_module_1`).
   - Mở file `.sql` tương ứng trong DBeaver (`Ctrl + O` chọn file `scripts/erd_modules/module_1_auth_rbac.sql` hoặc `scripts/erd_roles/role_1_customer.sql`).
   - Bấm `Alt + X` để thực thi toàn bộ script tạo bảng.
   - Bên cây điều hướng trái (Database Navigator), nhấp đúp vào schema vừa tạo -> Chọn tab **ER Diagram**.
   - DBeaver sẽ tự động sắp xếp các bảng và vẽ các đường nối quan hệ (Foreign Keys) đẹp mắt, không bị rối.
4. **Cách 2 - Xuất ảnh chất lượng cao**:
   - Nhấp chuột phải vào khoảng trống trên sơ đồ ERD -> Chọn **Save diagram as image...** (chọn định dạng PNG).
   - Chọn chế độ hiển thị: Có thể bấm nút góc trên thanh công cụ để ẩn bớt các cột phụ, chỉ hiển thị Khóa chính (PK) và Khóa ngoại (FK) để sơ đồ gọn gàng, chữ to rõ nét trong Docs.

---

## III. SƠ ĐỒ MERMAID ERD CHI TIẾT THEO TỪNG CỤC (XEM TRỰC TIẾP & CHỤP NGAY)

Bạn có thể bật tính năng **Markdown Preview** của VS Code (`Ctrl + Shift + V`) để xem ngay sơ đồ đồ họa của các khối dưới đây và dùng công cụ Snipping Tool (`Win + Shift + S`) chụp nhanh đưa vào docs.

### Cục 1: Phân hệ Xác thực & Phân quyền (Auth & RBAC)
```mermaid
erDiagram
    users ||--o{ user_roles : "has"
    roles ||--o{ user_roles : "assigned_to"
    roles ||--o{ role_permissions : "includes"
    permissions ||--o{ role_permissions : "granted_to"
    roles ||--o{ roles : "parent_role"
    users ||--o{ user_facility_scopes : "scoped_to"
    users ||--o{ sessions : "owns"
    users ||--o{ login_history : "logs"
    users ||--o{ auth_challenges : "receives"

    users {
        string id PK
        string email UK
        string full_name
        string phone
        string status
    }
    roles {
        string id PK
        string code UK
        string name
        string parent_role_id FK
    }
    permissions {
        string id PK
        string code UK
        string name
    }
    user_roles {
        string user_id PK,FK
        string role_id PK,FK
    }
    role_permissions {
        string role_id PK,FK
        string permission_id PK,FK
    }
    user_facility_scopes {
        string id PK
        string user_id FK
        string facility_id
        string scope_level
    }
    sessions {
        string id PK
        string user_id FK
        string token_hash
        timestamp expires_at
    }
    login_history {
        string id PK
        string user_id FK
        string email_attempted
        boolean success
        timestamp occurred_at
    }
    auth_challenges {
        string id PK
        string user_id FK
        string purpose
        string token_hash UK
        string otp_hash
        timestamp expires_at
    }
```

---

### Cục 2: Quản lý Cơ sở, Loại kho & Ô kho lưu trữ (Facilities & Units Inventory)
```mermaid
erDiagram
    facilities ||--o{ unit_types : "offers"
    facilities ||--o{ storage_units : "contains"
    facilities ||--o{ rental_package_policies : "applies"
    unit_types ||--o{ storage_units : "categorizes"

    facilities {
        string id PK
        string code UK
        string name
        string address
        string city
        string status
    }
    unit_types {
        string id PK
        string facility_id FK
        string code
        string name
        decimal length_m
        decimal width_m
        decimal height_m
        decimal monthly_price
        decimal max_load_kg
        string status
    }
    storage_units {
        string id PK
        string facility_id FK
        string unit_type_id FK
        string code UK
        string floor
        string zone
        string status
        timestamp available_from
    }
    rental_package_policies {
        string id PK
        string facility_id FK
        int duration_months
        decimal discount_percent
        decimal renewal_discount_percent
        string status
    }
```

---

### Cục 3: Báo giá, Đặt giữ chỗ & Check-in (Booking & Check-in)
```mermaid
erDiagram
    reservation_quotes ||--o| reservations : "originates"
    reservations ||--o{ reservation_goods_items : "contains"
    reservations ||--|| reservation_pricing_snapshots : "snapshots"
    reservations ||--|| reservation_email_verifications : "verifies"
    reservations ||--o{ unit_assignments : "assigns"
    reservations ||--|| check_ins : "checks_in"
    reservations ||--o| booking_documents : "generates"
    file_assets ||--|| booking_documents : "stores"

    reservation_quotes {
        string id PK
        string customer_id FK
        string facility_id FK
        string unit_type_id FK
        int rental_months
        decimal monthly_price
        decimal total_after_discount
        decimal security_deposit_amount
        decimal reservation_deposit_amount
        timestamp expires_at
    }
    reservations {
        string id PK
        string reservation_code UK
        string customer_id FK
        string facility_id FK
        string unit_type_id FK
        string storage_unit_id FK
        string source_quote_id FK
        string status
        date expected_start_date
        int rental_duration_months
        string goods_review_status
    }
    reservation_goods_items {
        string id PK
        string reservation_id FK
        string category
        string custom_name
        int quantity
        decimal weight_kg
        boolean is_fragile
        boolean requires_staff_review
        string review_status
    }
    reservation_pricing_snapshots {
        string id PK
        string reservation_id UK,FK
        decimal monthly_base_price
        decimal discount_amount
        decimal total_rental_price
        decimal deposit_amount
    }
    check_ins {
        string id PK
        string reservation_id UK,FK
        string performed_by FK
        timestamp checked_in_at
        string status
        text checklist_json
        string readiness_note
    }
    unit_assignments {
        string id PK
        string reservation_id FK
        string storage_unit_id FK
        string assigned_by FK
        timestamp assigned_at
    }
    booking_documents {
        string id PK
        string reservation_id UK,FK
        string file_asset_id UK,FK
        string document_type
        timestamp issued_at
    }
    file_assets {
        string id PK
        string uploaded_by FK
        string original_name
        string storage_key
        string content_type
        bigint size_bytes
    }
```

---

### Cục 4: Hợp đồng, Thanh toán, Gia hạn & Xử lý nợ (Rentals, Renewals & Overdue)
```mermaid
erDiagram
    rentals ||--|| contracts : "signs"
    rentals ||--o{ payments : "bills"
    payments ||--o{ refunds : "refunds"
    payments ||--o{ payment_complaints : "complains"
    rentals ||--o{ renewals : "requests_extension"
    rentals ||--o{ renewal_quotes : "quotes"
    renewals ||--o{ renewal_accepted_revisions : "revises"
    renewal_quotes ||--o| renewal_accepted_revisions : "accepts"
    renewals ||--|| renewal_workflows : "reviews"
    rentals ||--o| renewal_open_slots : "allocates"
    renewals ||--|| renewal_operation_states : "tracks_state"
    renewals ||--o{ renewal_operation_events : "logs_event"
    rentals ||--o| return_cases : "settles"
    rentals ||--o| overdue_follow_up_states : "monitors_overdue"
    overdue_follow_up_states ||--o{ overdue_follow_ups : "records_action"

    rentals {
        string id PK
        string reservation_id UK,FK
        string customer_id FK
        string storage_unit_id FK
        date start_date
        date next_billing_date
        string status
    }
    contracts {
        string id PK
        string rental_id UK,FK
        string contract_code UK
        text terms
        timestamp signed_at
        string status
    }
    payments {
        string id PK
        string rental_id FK
        decimal amount
        string purpose
        string method
        string status
        string transaction_ref
    }
    refunds {
        string id PK
        string payment_id FK
        decimal amount
        string reason
        string status
    }
    payment_complaints {
        string id PK
        string payment_id FK
        string customer_id FK
        string reason
        string status
    }
    renewals {
        string id PK
        string rental_id FK
        string requested_by FK
        int extension_months
        decimal additional_amount
        string status
    }
    renewal_quotes {
        string id PK
        string rental_id FK
        string customer_id FK
        longtext terms_json
        timestamp expires_at
    }
    renewal_workflows {
        string id PK,FK
        string accepted_revision_id FK
        string reviewer_id FK
        string review_reason
        timestamp payment_deadline
    }
    return_cases {
        string id PK
        string rental_id UK,FK
        string status
        date requested_date
        decimal deposit_amount
        decimal damage_fee
        decimal net_refund_amount
    }
    overdue_follow_up_states {
        string case_ref PK
        string rental_id FK
        string state
        decimal total_overdue_fee
    }
    overdue_follow_ups {
        string id PK
        string case_ref FK
        string actor_id FK
        string action_type
        string notes
    }
```

---

### Cục 5: Hỗ trợ Khách hàng & Bảo trì kho bãi (Support & Maintenance)
```mermaid
erDiagram
    support_tickets ||--o{ support_ticket_messages : "chat"
    support_tickets ||--o{ support_messages : "history"
    support_tickets ||--o{ support_escalations : "escalates"
    support_tickets ||--|| support_workflow_states : "state"
    support_tickets ||--o{ support_workflow_events : "events"
    storage_units ||--o{ maintenance_tasks : "undergoes"
    return_cases ||--o{ maintenance_tasks : "triggers"

    support_tickets {
        string id PK
        string ticket_code UK
        string customer_id FK
        string facility_id FK
        string assigned_to FK
        string title
        string category
        string priority
        string status
    }
    support_ticket_messages {
        string id PK
        string ticket_id FK
        string author_id FK
        text body
        timestamp created_at
    }
    support_escalations {
        string id PK
        string ticket_id FK
        string target_module
        string status
        string reason
        string requested_by FK
        string decided_by FK
    }
    support_workflow_states {
        string id PK,FK
        string accepted_by FK
        string resolved_by FK
        timestamp closed_at
    }
    maintenance_tasks {
        string id PK
        string facility_id FK
        string storage_unit_id FK
        string return_case_id FK
        string reported_by FK
        string assigned_to FK
        string title
        string reason
        string priority
        string status
        date due_at
        timestamp completed_at
    }
```

---

### Cục 6: Quản trị Hệ thống, File tài sản & Kiểm toán (System Assets & Audit)
```mermaid
erDiagram
    users ||--o{ file_assets : "uploads"
    users ||--o{ notifications : "receives"
    users ||--o{ activity_logs : "triggers"
    facilities ||--o{ activity_logs : "records_in"

    system_settings {
        string id PK
        string setting_key UK
        string setting_value
        string description
    }
    file_assets {
        string id PK
        string uploaded_by FK
        string original_name
        string storage_key
        string content_type
        bigint size_bytes
        string checksum_sha256
        string status
    }
    notifications {
        string id PK
        string user_id FK
        string title
        string message
        string type
        string action_url
        boolean is_read
    }
    activity_logs {
        string id PK
        string actor_id FK
        string facility_id FK
        string action
        string entity_type
        string entity_id
        string correlation_id
        text before_state_json
        text after_state_json
    }
```

---

## IV. SƠ ĐỒ MERMAID ERD THEO GÓC NHÌN VAI TRÒ (ROLE-BASED ERD)

Dành cho các mục trong tài liệu Docs phân tích theo góc nhìn từng đối tượng người dùng (Actors / Personas):

### 1. Góc nhìn Khách hàng (Customer Persona ERD)
```mermaid
erDiagram
    users ||--o{ reservation_quotes : "gets_quote"
    reservation_quotes ||--o| reservations : "converts_to"
    users ||--o{ reservations : "places_booking"
    reservations ||--o{ reservation_goods_items : "lists_goods"
    reservations ||--|| reservation_pricing_snapshots : "price_lock"
    reservations ||--|| rentals : "turns_into"
    rentals ||--|| contracts : "signs"
    rentals ||--o{ payments : "pays"
    payments ||--o{ payment_complaints : "complains"
    payments ||--o{ refunds : "receives_refund"
    rentals ||--o{ renewals : "extends"
    rentals ||--o| return_cases : "returns_unit"
    users ||--o{ support_tickets : "opens_ticket"
    support_tickets ||--o{ support_ticket_messages : "replies"
    users ||--o{ notifications : "notified"
```

### 2. Góc nhìn Nhân viên & Kỹ thuật viên (Staff & Technician Persona ERD)
```mermaid
erDiagram
    facilities ||--o{ storage_units : "manages"
    reservations ||--o{ reservation_goods_items : "inspects_goods"
    reservations ||--o{ unit_assignments : "assigns_unit"
    storage_units ||--o{ unit_assignments : "allocated"
    reservations ||--|| check_ins : "performs_checkin"
    check_ins ||--|| booking_documents : "hands_over"
    storage_units ||--o{ return_cases : "inspects_return"
    storage_units ||--o{ maintenance_tasks : "maintains"
    return_cases ||--o{ maintenance_tasks : "triggers_repair"
    support_tickets ||--o{ support_ticket_messages : "assists_customer"
```

### 3. Góc nhìn Quản lý & Kinh doanh (Manager & Business Persona ERD)
```mermaid
erDiagram
    facilities ||--o{ unit_types : "configures_pricing"
    facilities ||--o{ storage_units : "monitors_occupancy"
    facilities ||--o{ rental_package_policies : "sets_policies"
    rentals ||--|| contracts : "monitors_contracts"
    rentals ||--o{ payments : "reconciles_revenue"
    payments ||--o{ payment_complaints : "resolves_complaint"
    payments ||--o{ refunds : "approves_refund"
    rentals ||--o{ renewals : "reviews_renewal"
    renewals ||--|| renewal_workflows : "approves_workflow"
    rentals ||--o| overdue_follow_up_states : "monitors_overdue"
    overdue_follow_up_states ||--o{ overdue_follow_ups : "executes_repossession"
    support_tickets ||--o{ support_escalations : "handles_escalation"
```

### 4. Góc nhìn Quản trị viên (Admin Persona ERD)
```mermaid
erDiagram
    users ||--o{ user_roles : "grants_role"
    roles ||--o{ user_roles : "assigned"
    roles ||--o{ role_permissions : "grants_permission"
    permissions ||--o{ role_permissions : "assigned"
    users ||--o{ user_facility_scopes : "scopes_branch"
    users ||--o{ sessions : "manages_tokens"
    users ||--o{ login_history : "monitors_login_security"
    users ||--o{ auth_challenges : "2fa_and_recovery"
    system_settings {
        string setting_key
        string setting_value
    }
    activity_logs {
        string action
        string entity_type
        string actor_id
    }
```
