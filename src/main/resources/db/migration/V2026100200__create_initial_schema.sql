-- Initial schema for a new StorageHub database.
-- Keep this migration before V2026100201. Do not seed demo data here.

CREATE TABLE users (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    phone VARCHAR(30) NULL,
    permanent_address VARCHAR(500) NULL,
    emergency_contact_name VARCHAR(160) NULL,
    emergency_contact_phone VARCHAR(30) NULL,
    avatar_url LONGTEXT NULL,
    status VARCHAR(32) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB;

CREATE TABLE roles (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_roles_code UNIQUE (code)
) ENGINE=InnoDB;

CREATE TABLE permissions (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(160) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_permissions_code UNIQUE (code)
) ENGINE=InnoDB;

CREATE TABLE facilities (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_facilities_code UNIQUE (code)
) ENGINE=InnoDB;

CREATE TABLE user_roles (
    user_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id)
) ENGINE=InnoDB;

CREATE TABLE role_permissions (
    role_id BINARY(16) NOT NULL,
    permission_id BINARY(16) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
) ENGINE=InnoDB;

CREATE TABLE user_facility_scopes (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    user_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    scope_level VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_facility_scope UNIQUE (user_id, facility_id),
    CONSTRAINT fk_user_facility_scope_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_facility_scope_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
) ENGINE=InnoDB;

CREATE TABLE unit_types (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    length_m DECIMAL(10,2) NOT NULL,
    width_m DECIMAL(10,2) NOT NULL,
    height_m DECIMAL(10,2) NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    image_url VARCHAR(1000) NULL,
    max_load_kg DECIMAL(10,2) NULL,
    rack_count INT NULL,
    rack_length_m DECIMAL(10,2) NULL,
    rack_width_m DECIMAL(10,2) NULL,
    rack_height_m DECIMAL(10,2) NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_unit_types_facility_code UNIQUE (facility_id, code),
    CONSTRAINT fk_unit_types_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
) ENGINE=InnoDB;

CREATE TABLE storage_units (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    unit_type_id BINARY(16) NOT NULL,
    code VARCHAR(64) NOT NULL,
    floor VARCHAR(32) NULL,
    zone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    available_from TIMESTAMP(6) NULL,
    last_released_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_storage_units_code UNIQUE (code),
    CONSTRAINT fk_storage_units_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_storage_units_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id)
) ENGINE=InnoDB;

CREATE TABLE login_history (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    user_id BINARY(16) NULL,
    email_attempted VARCHAR(320) NOT NULL,
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    failure_reason VARCHAR(200) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_login_history_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE sessions (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    user_id BINARY(16) NOT NULL,
    token_hash VARCHAR(128) NULL,
    refresh_token_hash VARCHAR(128) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    refresh_expires_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    last_seen_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_sessions_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE auth_challenges (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    user_id BINARY(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    otp_hash VARCHAR(128) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_challenges_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_challenges_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE activity_logs (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    actor_id BINARY(16) NULL,
    facility_id BINARY(16) NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BINARY(16) NULL,
    before_state_json TEXT NULL,
    after_state_json TEXT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_activity_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_activity_logs_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
) ENGINE=InnoDB;

CREATE TABLE system_settings (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    setting_key VARCHAR(100) NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    label VARCHAR(160) NOT NULL,
    setting_type VARCHAR(20) NOT NULL,
    setting_value VARCHAR(2000) NOT NULL,
    options_json VARCHAR(4000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_system_settings_key UNIQUE (setting_key)
) ENGINE=InnoDB;

CREATE TABLE notifications (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    user_id BINARY(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    related_entity_id BINARY(16) NULL,
    is_read BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE file_assets (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    uploaded_by BINARY(16) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NULL,
    entity_type VARCHAR(64) NULL,
    entity_id BINARY(16) NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_file_assets_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE reservation_quotes (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    unit_type_id BINARY(16) NOT NULL,
    pricing_package_code VARCHAR(30) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    rental_months INT NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    subtotal DECIMAL(14,2) NOT NULL,
    discount_rate DECIMAL(5,4) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    total_after_discount DECIMAL(14,2) NOT NULL,
    reservation_deposit_amount DECIMAL(14,2) NOT NULL,
    security_deposit_amount DECIMAL(14,2) NOT NULL,
    remaining_rental_amount DECIMAL(14,2) NOT NULL,
    due_at_check_in DECIMAL(14,2) NOT NULL,
    total_initial_obligation DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_quotes_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_reservation_quotes_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_reservation_quotes_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id)
) ENGINE=InnoDB;

CREATE TABLE rental_package_policies (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    code VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    rental_months INT NOT NULL,
    discount_rate DECIMAL(5,4) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rental_package_policies_facility_code UNIQUE (facility_id, code),
    CONSTRAINT fk_rental_package_policies_facility FOREIGN KEY (facility_id) REFERENCES facilities(id)
) ENGINE=InnoDB;

CREATE TABLE reservations (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_code VARCHAR(32) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    source_quote_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NULL,
    facility_id BINARY(16) NOT NULL,
    unit_type_id BINARY(16) NOT NULL,
    assigned_unit_id BINARY(16) NULL,
    status VARCHAR(32) NOT NULL,
    goods_review_status VARCHAR(20) NOT NULL,
    goods_reviewed_by BINARY(16) NULL,
    goods_review_submitted_at TIMESTAMP(6) NULL,
    goods_review_due_at TIMESTAMP(6) NULL,
    goods_reviewed_at TIMESTAMP(6) NULL,
    goods_review_note VARCHAR(1000) NULL,
    compatibility_result VARCHAR(24) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    goods_condition VARCHAR(500) NULL,
    total_goods_volume_m3 DECIMAL(16,6) NOT NULL,
    total_goods_weight_kg DECIMAL(14,2) NOT NULL,
    hold_expires_at TIMESTAMP(6) NULL,
    payment_expires_at TIMESTAMP(6) NULL,
    complaint_expires_at TIMESTAMP(6) NULL,
    archived_at TIMESTAMP(6) NULL,
    confirmed_at TIMESTAMP(6) NULL,
    deposit_paid_at TIMESTAMP(6) NULL,
    cancelled_at TIMESTAMP(6) NULL,
    expired_at TIMESTAMP(6) NULL,
    rejected_at TIMESTAMP(6) NULL,
    cancel_reason VARCHAR(1000) NULL,
    rejection_reason VARCHAR(1000) NULL,
    notes VARCHAR(2000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservations_code UNIQUE (reservation_code),
    CONSTRAINT uk_reservations_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT fk_reservations_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_reservations_quote FOREIGN KEY (source_quote_id) REFERENCES reservation_quotes(id),
    CONSTRAINT fk_reservations_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_reservations_unit_type FOREIGN KEY (unit_type_id) REFERENCES unit_types(id),
    CONSTRAINT fk_reservations_assigned_unit FOREIGN KEY (assigned_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_reservations_goods_reviewer FOREIGN KEY (goods_reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE reservation_goods_items (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    category VARCHAR(40) NOT NULL,
    custom_goods_name VARCHAR(160) NULL,
    material_name VARCHAR(120) NULL,
    custom_material VARCHAR(120) NULL,
    description VARCHAR(1000) NULL,
    quantity INT NOT NULL,
    length_cm DECIMAL(10,2) NOT NULL,
    width_cm DECIMAL(10,2) NOT NULL,
    height_cm DECIMAL(10,2) NOT NULL,
    weight_kg DECIMAL(10,2) NOT NULL,
    fragile BOOLEAN NOT NULL,
    customer_note VARCHAR(1000) NULL,
    requires_staff_review BOOLEAN NOT NULL,
    review_status VARCHAR(20) NOT NULL,
    review_note VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_goods_items_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

CREATE TABLE reservation_pricing_snapshots (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    pricing_package_code VARCHAR(50) NOT NULL,
    pricing_policy_version VARCHAR(50) NOT NULL,
    rental_months INT NOT NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    gross_rental_amount DECIMAL(14,2) NOT NULL,
    discount_rate DECIMAL(5,4) NOT NULL,
    discount_amount DECIMAL(14,2) NOT NULL,
    net_rental_amount DECIMAL(14,2) NOT NULL,
    reservation_deposit_amount DECIMAL(14,2) NOT NULL,
    security_deposit_amount DECIMAL(14,2) NOT NULL,
    remaining_rental_amount DECIMAL(14,2) NOT NULL,
    due_at_check_in DECIMAL(14,2) NOT NULL,
    total_initial_obligation DECIMAL(14,2) NOT NULL,
    quoted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_pricing_snapshot_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_pricing_snapshot_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

CREATE TABLE reservation_email_verifications (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    otp_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    last_sent_at TIMESTAMP(6) NOT NULL,
    verified_at TIMESTAMP(6) NULL,
    failed_attempts INT NOT NULL,
    resend_count INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_email_verification_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_email_verification_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

CREATE TABLE rentals (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    storage_unit_id BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    start_date DATE NOT NULL,
    contract_end_date DATE NOT NULL,
    actual_returned_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    monthly_price DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rentals_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_rentals_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_rentals_storage_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_rentals_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

CREATE TABLE contracts (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    contract_number VARCHAR(64) NOT NULL,
    sent_at TIMESTAMP(6) NULL,
    signed_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NULL,
    signed_by VARCHAR(64) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_contracts_number UNIQUE (contract_number),
    CONSTRAINT fk_contracts_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)
) ENGINE=InnoDB;

CREATE TABLE payments (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    initiated_by BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP(6) NULL,
    paid_at TIMESTAMP(6) NULL,
    failure_code VARCHAR(100) NULL,
    failure_reason VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payments_initiated_by FOREIGN KEY (initiated_by) REFERENCES users(id),
    CONSTRAINT fk_payments_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

CREATE TABLE payment_complaints (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    payment_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    submitted_at TIMESTAMP(6) NOT NULL,
    review_due_at TIMESTAMP(6) NOT NULL,
    reviewed_by BINARY(16) NULL,
    reviewed_at TIMESTAMP(6) NULL,
    withdrawn_at TIMESTAMP(6) NULL,
    decision_reason VARCHAR(2000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_complaints_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_payment_complaints_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_payment_complaints_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_payment_complaints_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_payment_complaints_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE refunds (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    payment_id BINARY(16) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    gateway_refund_id VARCHAR(100) NULL,
    reason VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_refunds_requested_by FOREIGN KEY (requested_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE renewals (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    new_end_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewals_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_renewals_requested_by FOREIGN KEY (requested_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE return_cases (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    storage_unit_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    status VARCHAR(48) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    scheduled_date DATE NOT NULL,
    customer_notes VARCHAR(2000) NULL,
    inspected_by BINARY(16) NULL,
    inspected_at TIMESTAMP(6) NULL,
    inventory_match VARCHAR(20) NULL,
    damage_classification VARCHAR(32) NULL,
    inspection_notes VARCHAR(4000) NULL,
    evidence_photos_json VARCHAR(4000) NULL,
    returned_key BOOLEAN NOT NULL DEFAULT FALSE,
    returned_card BOOLEAN NOT NULL DEFAULT FALSE,
    returned_lock BOOLEAN NOT NULL DEFAULT FALSE,
    proposed_unit_status VARCHAR(32) NULL,
    deposit_deduction DECIMAL(14,2) NOT NULL DEFAULT 0,
    damage_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    cleaning_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    lost_item_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    overdue_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    outstanding_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_deductions DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_refund DECIMAL(14,2) NOT NULL DEFAULT 0,
    amount_due DECIMAL(14,2) NOT NULL DEFAULT 0,
    overdue_days INT NULL,
    customer_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    customer_confirmed_at TIMESTAMP(6) NULL,
    customer_decision VARCHAR(20) NULL,
    customer_decision_note VARCHAR(2000) NULL,
    reviewed_by BINARY(16) NULL,
    reviewed_at TIMESTAMP(6) NULL,
    manager_resolution_note VARCHAR(2000) NULL,
    settlement_payment_id VARCHAR(64) NULL,
    settlement_paid_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_return_cases_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_return_cases_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_return_cases_storage_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_return_cases_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_return_cases_requested_by FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT fk_return_cases_inspected_by FOREIGN KEY (inspected_by) REFERENCES users(id),
    CONSTRAINT fk_return_cases_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE check_ins (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    rental_id BINARY(16) NULL,
    performed_by BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    scheduled_at TIMESTAMP(6) NULL,
    checked_in_at TIMESTAMP(6) NULL,
    checklist_json VARCHAR(8000) NULL,
    readiness_note VARCHAR(1000) NULL,
    rejection_reason VARCHAR(1000) NULL,
    rejection_disposition VARCHAR(20) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_check_in_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_check_ins_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_check_ins_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_check_ins_performed_by FOREIGN KEY (performed_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE unit_assignments (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    storage_unit_id BINARY(16) NOT NULL,
    assigned_by BINARY(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    assigned_at TIMESTAMP(6) NOT NULL,
    cancelled_by BINARY(16) NULL,
    cancelled_at TIMESTAMP(6) NULL,
    cancel_reason VARCHAR(1000) NULL,
    cancellation_idempotency_key VARCHAR(100) NULL,
    cancellation_request_fingerprint VARCHAR(64) NULL,
    release_disposition VARCHAR(20) NULL,
    previous_storage_unit_status VARCHAR(16) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_unit_assignment_cancellation UNIQUE (cancelled_by, cancellation_idempotency_key),
    CONSTRAINT fk_unit_assignments_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_unit_assignments_storage_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_unit_assignments_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(id),
    CONSTRAINT fk_unit_assignments_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE booking_documents (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    file_asset_id BINARY(16) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_booking_documents_reservation UNIQUE (reservation_id),
    CONSTRAINT uk_booking_documents_file_asset UNIQUE (file_asset_id),
    CONSTRAINT fk_booking_documents_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_booking_documents_file_asset FOREIGN KEY (file_asset_id) REFERENCES file_assets(id)
) ENGINE=InnoDB;

CREATE TABLE support_tickets (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NULL,
    assigned_to BINARY(16) NULL,
    status VARCHAR(24) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_tickets_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_support_tickets_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_support_tickets_assigned_to FOREIGN KEY (assigned_to) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE maintenance_tasks (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    storage_unit_id BINARY(16) NOT NULL,
    return_case_id BINARY(16) NULL,
    title VARCHAR(255) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    damage_classification VARCHAR(32) NULL,
    reported_by BINARY(16) NOT NULL,
    assigned_to BINARY(16) NULL,
    status VARCHAR(24) NOT NULL,
    due_at DATE NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    result_report VARCHAR(4000) NULL,
    evidence_photos_json VARCHAR(4000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_maintenance_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_maintenance_tasks_storage_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_maintenance_tasks_return_case FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_maintenance_tasks_reported_by FOREIGN KEY (reported_by) REFERENCES users(id),
    CONSTRAINT fk_maintenance_tasks_assigned_to FOREIGN KEY (assigned_to) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE support_ticket_messages (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id),
    CONSTRAINT fk_support_ticket_messages_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB;
