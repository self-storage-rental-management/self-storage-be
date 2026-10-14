-- ==============================================================================
-- MODULE 3: BOOKING, GOODS REVIEW & CHECK-IN (ĐẶT GIỮ CHỖ & NHẬN KHO)
-- ==============================================================================

-- Bảng tham chiếu liên kết ngoài (Reference Stubs)
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL
);

CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(32) NOT NULL
);

CREATE TABLE unit_types (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(20) NOT NULL
);

CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL
);

-- Các bảng chính của Phân hệ Đặt chỗ & Check-in
CREATE TABLE reservation_quotes (
    id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    pricing_package_code VARCHAR(30) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    rental_months INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    subtotal DECIMAL(14,2) NOT NULL,
    discount_rate DECIMAL(5,4) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    total_after_discount DECIMAL(14,2) NOT NULL,
    security_deposit_amount DECIMAL(14,2) NOT NULL,
    reservation_deposit_amount DECIMAL(14,2) NOT NULL,
    remaining_rental_amount DECIMAL(14,2) NOT NULL,
    total_initial_obligation DECIMAL(14,2) NOT NULL,
    due_at_check_in DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_quotes_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_quotes_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_quotes_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id)
);

CREATE TABLE reservations (
    id VARCHAR(36) NOT NULL,
    reservation_code VARCHAR(32) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NULL,
    source_quote_id VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT',
    expected_start_date DATE NOT NULL,
    rental_duration_months INT NOT NULL,
    total_goods_volume_m3 DECIMAL(10,2) NULL,
    total_goods_weight_kg DECIMAL(10,2) NULL,
    goods_review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    goods_reviewed_by VARCHAR(36) NULL,
    hold_expires_at TIMESTAMP NULL,
    payment_expires_at TIMESTAMP NULL,
    confirmed_at TIMESTAMP NULL,
    deposit_paid_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (reservation_code),
    CONSTRAINT fk_reservations_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_reservations_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_reservations_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id),
    CONSTRAINT fk_reservations_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_reservations_source_quote FOREIGN KEY (source_quote_id) REFERENCES reservation_quotes(id),
    CONSTRAINT fk_reservations_reviewer FOREIGN KEY (goods_reviewed_by) REFERENCES users(id)
);

CREATE TABLE reservation_goods_items (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    category VARCHAR(64) NOT NULL,
    custom_name VARCHAR(160) NULL,
    description VARCHAR(500) NULL,
    quantity INT NOT NULL DEFAULT 1,
    length_m DECIMAL(10,2) NOT NULL,
    width_m DECIMAL(10,2) NOT NULL,
    height_m DECIMAL(10,2) NOT NULL,
    weight_kg DECIMAL(10,2) NOT NULL,
    is_fragile BOOLEAN NOT NULL DEFAULT FALSE,
    requires_staff_review BOOLEAN NOT NULL DEFAULT FALSE,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    review_notes VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_goods_items_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

CREATE TABLE reservation_pricing_snapshots (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    duration_months INT NOT NULL,
    monthly_base_price DECIMAL(14,2) NOT NULL,
    discount_percent DECIMAL(5,2) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    total_rental_price DECIMAL(14,2) NOT NULL,
    deposit_amount DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    quote_expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_snapshots_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

CREATE TABLE reservation_email_verifications (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_sent_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    resend_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_verifications_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

CREATE TABLE check_ins (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    staff_id VARCHAR(36) NULL,
    scheduled_at TIMESTAMP NULL,
    checked_in_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    checklist_json TEXT NULL,
    readiness_note VARCHAR(1000) NULL,
    rejection_reason VARCHAR(1000) NULL,
    rejection_disposition VARCHAR(20) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_check_ins_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_check_ins_staff FOREIGN KEY (staff_id) REFERENCES users(id)
);

CREATE TABLE unit_assignments (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    assigned_by VARCHAR(36) NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignments_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_assignments_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id) ON DELETE CASCADE,
    CONSTRAINT fk_assignments_user FOREIGN KEY (assigned_by) REFERENCES users(id)
);

CREATE TABLE file_assets (
    id VARCHAR(36) NOT NULL,
    uploaded_by VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NULL,
    entity_id VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_file_assets_uploader FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

CREATE TABLE booking_documents (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    file_asset_id VARCHAR(36) NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (file_asset_id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_documents_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_documents_file_asset FOREIGN KEY (file_asset_id) REFERENCES file_assets(id) ON DELETE CASCADE
);
