-- ==============================================================================
-- ROLE 3: MANAGER & BUSINESS OPERATIONS VIEW (GÓC NHÌN QUẢN LÝ & KINH DOANH)
-- ==============================================================================
-- Mô tả: Chứa toàn bộ các bảng phục vụ công tác quản trị kinh doanh, bảng giá, duyệt gia hạn,
-- đối soát dòng tiền thanh toán/hoàn tiền, xử lý quá hạn và giải quyết khiếu nại chuyển cấp.

-- 1. Bảng tham chiếu người dùng
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL
);

-- 2. Quản lý Cơ sở, Loại ô kho & Chính sách gói thuê (Catalog & Pricing Policies)
CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
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
    max_load_kg DECIMAL(10,2) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_mgr_type_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    facility_id VARCHAR(36) NOT NULL,
    unit_type_id VARCHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL UNIQUE,
    floor VARCHAR(32) NULL,
    zone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT fk_mgr_unit_fac FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_mgr_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id)
);

CREATE TABLE rental_package_policies (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    facility_id VARCHAR(36) NULL,
    duration_months INT NOT NULL,
    discount_percent DECIMAL(5,2) NOT NULL,
    renewal_discount_percent DECIMAL(5,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_mgr_policy_fac FOREIGN KEY (facility_id) REFERENCES facilities(id)
);

-- 3. Quản lý Hợp đồng & Doanh thu thanh toán (Contracts & Payments)
CREATE TABLE rentals (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    start_date DATE NOT NULL,
    next_billing_date DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_mgr_rental_cust FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_mgr_rental_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id)
);

CREATE TABLE contracts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL UNIQUE,
    contract_code VARCHAR(64) NOT NULL UNIQUE,
    terms TEXT NOT NULL,
    signed_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    CONSTRAINT fk_mgr_contract_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

CREATE TABLE payments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NULL,
    amount DECIMAL(14,2) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    transaction_ref VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mgr_pay_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)
);

CREATE TABLE payment_complaints (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    reviewed_by VARCHAR(36) NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    resolution_note VARCHAR(1000) NULL,
    CONSTRAINT fk_mgr_complaint_pay FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_complaint_cust FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_mgr_complaint_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
);

CREATE TABLE refunds (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(36) NULL,
    amount DECIMAL(14,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMP NULL,
    CONSTRAINT fk_mgr_refund_pay FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_refund_requester FOREIGN KEY (requested_by) REFERENCES users(id)
);

-- 4. Quy trình Phê duyệt Gia hạn hợp đồng (Renewal Workflow & Slots)
CREATE TABLE renewals (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    extension_months INT NOT NULL,
    additional_amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT fk_mgr_ren_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_ren_user FOREIGN KEY (requested_by) REFERENCES users(id)
);

CREATE TABLE renewal_quotes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    terms_json LONGTEXT NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_mgr_renquote_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_renquote_user FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE renewal_accepted_revisions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    renewal_id VARCHAR(36) NOT NULL,
    quote_id VARCHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_mgr_renrev_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_renrev_quote FOREIGN KEY (quote_id) REFERENCES renewal_quotes(id) ON DELETE CASCADE
);

CREATE TABLE renewal_workflows (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    accepted_revision_id VARCHAR(36) NOT NULL,
    reviewer_id VARCHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    review_reason VARCHAR(2000) NULL,
    cancellation_reason VARCHAR(2000) NULL,
    payment_deadline TIMESTAMP NULL,
    CONSTRAINT fk_mgr_wf_renewal FOREIGN KEY (id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_wf_revision FOREIGN KEY (accepted_revision_id) REFERENCES renewal_accepted_revisions(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_wf_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id)
);

CREATE TABLE renewal_open_slots (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    renewal_id VARCHAR(36) NOT NULL UNIQUE,
    CONSTRAINT fk_mgr_slots_rental FOREIGN KEY (id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_slots_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id) ON DELETE CASCADE
);

CREATE TABLE renewal_operation_states (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    phase VARCHAR(24) NOT NULL,
    recovery_cutoff TIMESTAMP NULL,
    policy_ref VARCHAR(200) NULL,
    policy_version VARCHAR(100) NULL,
    completed_by VARCHAR(36) NULL,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_mgr_renops_renewal FOREIGN KEY (id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_renops_completer FOREIGN KEY (completed_by) REFERENCES users(id)
);

-- 5. Xử lý Nợ quá hạn & Thu hồi kho (Overdue Tracking & Repossession)
CREATE TABLE overdue_follow_up_states (
    case_ref VARCHAR(64) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    total_overdue_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mgr_overdue_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

CREATE TABLE overdue_follow_ups (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    case_ref VARCHAR(64) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    notes VARCHAR(2000) NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mgr_followup_case FOREIGN KEY (case_ref) REFERENCES overdue_follow_up_states(case_ref) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_followup_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);

CREATE TABLE return_cases (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL UNIQUE,
    reviewed_by VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_date DATE NOT NULL,
    deposit_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    damage_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    outstanding_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    amount_due_from_customer DECIMAL(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_mgr_return_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_return_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
);

-- 6. Tiếp nhận Chuyển cấp Hỗ trợ (Support Escalations)
CREATE TABLE support_tickets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_code VARCHAR(32) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
);

CREATE TABLE support_escalations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL,
    target_module VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    decision_reason VARCHAR(2000) NULL,
    requested_by VARCHAR(36) NULL,
    decided_by VARCHAR(36) NULL,
    requested_at TIMESTAMP NULL,
    decided_at TIMESTAMP NULL,
    CONSTRAINT fk_mgr_esc_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_mgr_esc_requester FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT fk_mgr_esc_decider FOREIGN KEY (decided_by) REFERENCES users(id)
);
