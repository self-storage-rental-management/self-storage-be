-- ==============================================================================
-- ROLE 1: CUSTOMER VIEW (GÓC NHÌN NGHIỆP VỤ KHÁCH HÀNG)
-- ==============================================================================
-- Mô tả: Chứa toàn bộ các bảng mà Khách hàng (Customer) trực tiếp sở hữu, tạo dữ liệu
-- hoặc tương tác trong suốt hành trình thuê kho (Catalog -> Đặt chỗ -> Ký HĐ -> Thanh toán -> Gia hạn/Trả kho -> Hỗ trợ).

-- 1. Tài khoản khách hàng
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    full_name VARCHAR(160) NOT NULL,
    phone VARCHAR(30) NULL,
    permanent_address VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
);

-- 2. Cơ sở và Loại ô kho khách hàng tìm kiếm
CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL
);

CREATE TABLE unit_types (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    facility_id VARCHAR(36) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    length_m DECIMAL(10,2) NOT NULL,
    width_m DECIMAL(10,2) NOT NULL,
    height_m DECIMAL(10,2) NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    CONSTRAINT fk_customer_unittype_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

-- 3. Báo giá & Đơn đặt chỗ (Booking)
CREATE TABLE reservation_quotes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    pricing_package_code VARCHAR(30) NOT NULL,
    rental_months INT NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    total_after_discount DECIMAL(14,2) NOT NULL,
    security_deposit_amount DECIMAL(14,2) NOT NULL,
    reservation_deposit_amount DECIMAL(14,2) NOT NULL,
    due_at_check_in DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_customer_quotes_user FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_customer_quotes_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_customer_quotes_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id)
);

CREATE TABLE reservations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_code VARCHAR(32) NOT NULL UNIQUE,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    source_quote_id VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    expected_start_date DATE NOT NULL,
    rental_duration_months INT NOT NULL,
    total_goods_volume_m3 DECIMAL(10,2) NULL,
    total_goods_weight_kg DECIMAL(10,2) NULL,
    goods_review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    payment_expires_at TIMESTAMP NULL,
    deposit_paid_at TIMESTAMP NULL,
    CONSTRAINT fk_customer_res_user FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_customer_res_fac FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_customer_res_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id),
    CONSTRAINT fk_customer_res_quote FOREIGN KEY (source_quote_id) REFERENCES reservation_quotes(id)
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
    CONSTRAINT fk_customer_goods_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

CREATE TABLE reservation_pricing_snapshots (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL UNIQUE,
    duration_months INT NOT NULL,
    monthly_base_price DECIMAL(14,2) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    total_rental_price DECIMAL(14,2) NOT NULL,
    deposit_amount DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    quote_expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_customer_snapshot_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE
);

-- 4. Hợp đồng thuê (Rentals & Contracts)
CREATE TABLE rentals (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL UNIQUE,
    customer_id VARCHAR(36) NOT NULL,
    start_date DATE NOT NULL,
    next_billing_date DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_customer_rental_res FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_customer_rental_user FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE contracts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL UNIQUE,
    contract_code VARCHAR(64) NOT NULL UNIQUE,
    terms TEXT NOT NULL,
    signed_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    CONSTRAINT fk_customer_contract_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

-- 5. Thanh toán & Khiếu nại (Payments & Complaints)
CREATE TABLE payments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(36) NULL,
    rental_id VARCHAR(36) NULL,
    amount DECIMAL(14,2) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    transaction_ref VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_pay_res FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_customer_pay_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)
);

CREATE TABLE payment_complaints (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence_url VARCHAR(1000) NULL,
    resolution_note VARCHAR(1000) NULL,
    CONSTRAINT fk_customer_complaint_pay FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_complaint_user FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE refunds (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMP NULL,
    CONSTRAINT fk_customer_refund_pay FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE
);

-- 6. Gia hạn & Trả kho (Renewals & Return)
CREATE TABLE renewal_quotes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    terms_json LONGTEXT NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_customer_renquote_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_renquote_user FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE renewals (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    extension_months INT NOT NULL,
    additional_amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT fk_customer_renewal_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_renewal_user FOREIGN KEY (requested_by) REFERENCES users(id)
);

CREATE TABLE return_cases (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_date DATE NOT NULL,
    deposit_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    damage_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_customer_return_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

-- 7. Chăm sóc khách hàng & Thông báo (Support & Notifications)
CREATE TABLE support_tickets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_code VARCHAR(32) NOT NULL UNIQUE,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT fk_customer_ticket_user FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_customer_ticket_fac FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

CREATE TABLE support_ticket_messages (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_msg_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_msg_author FOREIGN KEY (author_id) REFERENCES users(id)
);

CREATE TABLE notifications (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    type VARCHAR(32) NOT NULL,
    action_url VARCHAR(500) NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_notif_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
