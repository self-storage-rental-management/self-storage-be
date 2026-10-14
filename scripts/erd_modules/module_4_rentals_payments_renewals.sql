-- ==============================================================================
-- MODULE 4: RENTALS, PAYMENTS, RENEWALS & RETURNS (HỢP ĐỒNG, THANH TOÁN & GIA HẠN)
-- ==============================================================================

-- Bảng tham chiếu liên kết ngoài (Reference Stubs)
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL
);

CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(64) NOT NULL
);

CREATE TABLE reservations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    reservation_code VARCHAR(32) NOT NULL
);

-- Các bảng chính của Phân hệ Hợp đồng, Vận hành thuê & Thanh toán
CREATE TABLE rentals (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    start_date DATE NOT NULL,
    next_billing_date DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (reservation_id),
    CONSTRAINT fk_rentals_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_rentals_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_rentals_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id)
);

CREATE TABLE contracts (
    id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    contract_code VARCHAR(64) NOT NULL,
    terms TEXT NOT NULL,
    signed_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (rental_id),
    UNIQUE (contract_code),
    CONSTRAINT fk_contracts_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

CREATE TABLE payments (
    id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NULL,
    rental_id VARCHAR(36) NULL,
    amount DECIMAL(14,2) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    transaction_ref VARCHAR(100) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_payments_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_payments_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)
);

CREATE TABLE payment_complaints (
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence_url VARCHAR(1000) NULL,
    resolution_note VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_complaints_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE,
    CONSTRAINT fk_complaints_customer FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE refunds (
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE CASCADE
);

CREATE TABLE renewals (
    id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    extension_months INT NOT NULL,
    additional_amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewals_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewals_requester FOREIGN KEY (requested_by) REFERENCES users(id)
);

CREATE TABLE renewal_quotes (
    id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    terms_json LONGTEXT NOT NULL,
    terms_hash VARCHAR(64) NOT NULL,
    quoted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_quotes_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_quotes_customer FOREIGN KEY (customer_id) REFERENCES users(id)
);

CREATE TABLE renewal_accepted_revisions (
    id VARCHAR(36) NOT NULL,
    renewal_id VARCHAR(36) NOT NULL,
    quote_id VARCHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    note LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (renewal_id, revision_number),
    UNIQUE (quote_id),
    CONSTRAINT fk_renewal_rev_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_rev_quote FOREIGN KEY (quote_id) REFERENCES renewal_quotes(id) ON DELETE CASCADE
);

CREATE TABLE renewal_workflows (
    id VARCHAR(36) NOT NULL,
    accepted_revision_id VARCHAR(36) NOT NULL,
    reviewer_id VARCHAR(36) NULL,
    reviewed_at TIMESTAMP NULL,
    review_reason VARCHAR(2000) NULL,
    cancellation_reason VARCHAR(2000) NULL,
    payment_deadline TIMESTAMP NULL,
    extension_hold_ref VARCHAR(36) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_wf_renewal FOREIGN KEY (id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_wf_revision FOREIGN KEY (accepted_revision_id) REFERENCES renewal_accepted_revisions(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_wf_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id)
);

CREATE TABLE renewal_open_slots (
    id VARCHAR(36) NOT NULL,
    renewal_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (renewal_id),
    CONSTRAINT fk_open_slots_rental FOREIGN KEY (id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT fk_open_slots_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id) ON DELETE CASCADE
);

CREATE TABLE renewal_operation_states (
    id VARCHAR(36) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    deposit_payment_ref VARCHAR(36) NULL,
    deposit_paid_at TIMESTAMP NULL,
    original_signing_deadline TIMESTAMP NULL,
    effective_signing_deadline TIMESTAMP NULL,
    recovery_cutoff TIMESTAMP NULL,
    appointment_ref VARCHAR(36) NULL,
    appointment_start TIMESTAMP NULL,
    appointment_end TIMESTAMP NULL,
    arrival_ref VARCHAR(36) NULL,
    confirmed_exception_ref VARCHAR(36) NULL,
    pending_exception_ref VARCHAR(36) NULL,
    completed_by VARCHAR(36) NULL,
    completed_at TIMESTAMP NULL,
    policy_ref VARCHAR(200) NULL,
    policy_version VARCHAR(100) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_ops_state_renewal FOREIGN KEY (id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_ops_state_completer FOREIGN KEY (completed_by) REFERENCES users(id)
);

CREATE TABLE renewal_operation_events (
    id VARCHAR(36) NOT NULL,
    renewal_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NULL,
    payload_json LONGTEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_events_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id) ON DELETE CASCADE,
    CONSTRAINT fk_renewal_events_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);

CREATE TABLE renewal_idempotency (
    id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_key_hash VARCHAR(64) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (actor_id, operation, request_key_hash),
    CONSTRAINT fk_renewal_idempotency_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);

CREATE TABLE return_cases (
    id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_date DATE NOT NULL,
    completed_at TIMESTAMP NULL,
    deposit_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    damage_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    cleaning_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    outstanding_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    net_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    amount_due_from_customer DECIMAL(14,2) NOT NULL DEFAULT 0,
    notes VARCHAR(2000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (rental_id),
    CONSTRAINT fk_return_cases_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

CREATE TABLE overdue_follow_up_states (
    case_ref VARCHAR(64) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    total_overdue_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_overdue_state_rental FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE
);

CREATE TABLE overdue_follow_ups (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    case_ref VARCHAR(64) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    notes VARCHAR(2000) NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_follow_ups_case FOREIGN KEY (case_ref) REFERENCES overdue_follow_up_states(case_ref) ON DELETE CASCADE,
    CONSTRAINT fk_follow_ups_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);
