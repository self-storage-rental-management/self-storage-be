-- ==============================================================================
-- ROLE 2: STAFF & TECHNICIAN VIEW (GÓC NHÌN NHÂN VIÊN VẬN HÀNH & KỸ THUẬT VIÊN)
-- ==============================================================================
-- Mô tả: Chứa toàn bộ các bảng mà Nhân viên chi nhánh (Staff) và Kỹ thuật viên bảo trì
-- (Technician) trực tiếp thao tác tại cơ sở (Kiểm tra hàng, check-in, gán kho, bảo trì, hỗ trợ).

-- 1. Bảng tham chiếu người dùng & cơ sở
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL,
    phone VARCHAR(30) NULL
);

CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL
);

-- 2. Quản lý Ô kho tại cơ sở (Storage Units Inventory)
CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    facility_id VARCHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL UNIQUE,
    floor VARCHAR(32) NULL,
    zone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    available_from TIMESTAMP NULL,
    CONSTRAINT fk_staff_unit_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

-- 3. Tiếp nhận đặt kho & Thẩm định hàng hóa (Goods Inspection & Unit Assignment)
CREATE TABLE reservations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_code VARCHAR(32) NOT NULL UNIQUE,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    expected_start_date DATE NOT NULL,
    goods_review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    goods_reviewed_by VARCHAR(36) NULL,
    CONSTRAINT fk_staff_res_cust FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_staff_res_fac FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_staff_res_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_staff_res_reviewer FOREIGN KEY (goods_reviewed_by) REFERENCES users(id)
);

CREATE TABLE reservation_goods_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL,
    category VARCHAR(64) NOT NULL,
    custom_name VARCHAR(160) NULL,
    quantity INT NOT NULL DEFAULT 1,
    weight_kg DECIMAL(10,2) NOT NULL,
    is_fragile BOOLEAN NOT NULL DEFAULT FALSE,
    requires_staff_review BOOLEAN NOT NULL DEFAULT FALSE,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    review_notes VARCHAR(500) NULL,
    CONSTRAINT fk_staff_goods_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

CREATE TABLE unit_assignments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    assigned_by VARCHAR(36) NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(500) NULL,
    CONSTRAINT fk_staff_assign_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_assign_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_assign_staff FOREIGN KEY (assigned_by) REFERENCES users(id)
);

-- 4. Bàn giao & Check-in (Handover & Check-in Checklist)
CREATE TABLE check_ins (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL UNIQUE,
    performed_by VARCHAR(36) NOT NULL,
    scheduled_at TIMESTAMP NULL,
    checked_in_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    checklist_json TEXT NULL,
    readiness_note VARCHAR(1000) NULL,
    rejection_reason VARCHAR(1000) NULL,
    CONSTRAINT fk_staff_checkin_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_checkin_performer FOREIGN KEY (performed_by) REFERENCES users(id)
);

CREATE TABLE file_assets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    uploaded_by VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_staff_files_uploader FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

CREATE TABLE booking_documents (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL UNIQUE,
    file_asset_id VARCHAR(36) NOT NULL UNIQUE,
    document_type VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(500) NULL,
    CONSTRAINT fk_staff_doc_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_doc_file FOREIGN KEY (file_asset_id) REFERENCES file_assets(id) ON DELETE CASCADE
);

-- 5. Nghiệm thu trả kho & Bảo trì sửa chữa (Return Inspection & Maintenance Tasks)
CREATE TABLE return_cases (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    storage_unit_id VARCHAR(36) NOT NULL,
    inspected_by VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_date DATE NOT NULL,
    damage_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    cleaning_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    notes VARCHAR(2000) NULL,
    CONSTRAINT fk_staff_return_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_staff_return_inspector FOREIGN KEY (inspected_by) REFERENCES users(id)
);

CREATE TABLE maintenance_tasks (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    facility_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    return_case_id VARCHAR(36) NULL,
    reported_by VARCHAR(36) NOT NULL,
    assigned_to VARCHAR(36) NULL,
    title VARCHAR(255) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    damage_classification VARCHAR(32) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    due_at DATE NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    result_report VARCHAR(4000) NULL,
    CONSTRAINT fk_maint_fac FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_maint_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_maint_return FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_maint_reporter FOREIGN KEY (reported_by) REFERENCES users(id),
    CONSTRAINT fk_maint_assignee FOREIGN KEY (assigned_to) REFERENCES users(id)
);

-- 6. Hỗ trợ CSKH tại quầy / qua hệ thống (Support Ticket Resolution)
CREATE TABLE support_tickets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_code VARCHAR(32) NOT NULL UNIQUE,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    assigned_to VARCHAR(36) NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT fk_staff_ticket_cust FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_staff_ticket_fac FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_staff_ticket_staff FOREIGN KEY (assigned_to) REFERENCES users(id)
);

CREATE TABLE support_ticket_messages (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_staff_msg_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_msg_author FOREIGN KEY (author_id) REFERENCES users(id)
);

CREATE TABLE support_messages (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    author_role VARCHAR(16) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    evidence_json VARCHAR(500) NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_staff_suppmsg_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_suppmsg_author FOREIGN KEY (author_id) REFERENCES users(id)
);
