-- Unify Setup & Inspection arrival deadline (D), attendance reconciliation (R),
-- 2-attempt identity verification (V/F = V + 72h), and idempotency tracking.
-- Legacy columns (setup_completion_deadline_at, inventory_final_deadline_at) are preserved
-- so existing active rentals are not retroactively broken or failed.

ALTER TABLE rentals
    ADD COLUMN original_inspection_arrival_deadline_at DATETIME(6) NULL,
    ADD COLUMN intake_policy_version VARCHAR(64) NULL,
    ADD COLUMN intake_initial_duration_hours INT NULL,
    ADD COLUMN intake_extension_max_hours INT NULL,
    ADD COLUMN intake_extension_max_count INT NULL,
    ADD COLUMN intake_completion_hours INT NULL,
    ADD COLUMN appointment_status VARCHAR(32) NULL,
    ADD COLUMN appointment_version INT NOT NULL DEFAULT 0,
    ADD COLUMN actual_arrived_at DATETIME(6) NULL,
    ADD COLUMN arrival_recorded_at DATETIME(6) NULL,
    ADD COLUMN arrival_recorded_by BINARY(16) NULL,
    ADD COLUMN attendance_evidence_note VARCHAR(500) NULL,
    ADD COLUMN attendance_evidence_file_id BINARY(16) NULL,
    ADD COLUMN attendance_review_deadline_at DATETIME(6) NULL,
    ADD COLUMN attendance_review_status VARCHAR(32) NULL,
    ADD COLUMN attendance_review_owner_id BINARY(16) NULL,
    ADD COLUMN first_identity_verification_at DATETIME(6) NULL,
    ADD COLUMN inspection_completion_deadline_at DATETIME(6) NULL,
    ADD COLUMN verification_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN inspection_started_at DATETIME(6) NULL,
    ADD COLUMN inspection_completed_at DATETIME(6) NULL,
    ADD COLUMN inspection_failure_reason VARCHAR(500) NULL,
    ADD COLUMN interruption_reason VARCHAR(500) NULL,
    ADD COLUMN exception_owner_id BINARY(16) NULL,
    ADD COLUMN exception_due_at DATETIME(6) NULL,
    ADD COLUMN exception_reason VARCHAR(500) NULL,
    ADD COLUMN last_missed_reminder_at DATETIME(6) NULL,
    ADD COLUMN intake_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE intake_verification_attempts (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    substantive_ordinal INT NULL,
    outcome VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    staff_id BINARY(16) NOT NULL,
    document_number_masked VARCHAR(64) NULL,
    evidence_file_id BINARY(16) NULL,
    reason VARCHAR(500) NULL,
    idempotency_key VARCHAR(128) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_intake_verif_rental FOREIGN KEY (rental_id) REFERENCES rentals (id),
    CONSTRAINT uk_intake_verif_ordinal UNIQUE (rental_id, substantive_ordinal)
) ENGINE=InnoDB;

CREATE TABLE intake_idempotency_records (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    response_json LONGTEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_intake_idem_rental FOREIGN KEY (rental_id) REFERENCES rentals (id),
    CONSTRAINT uk_intake_idem UNIQUE (rental_id, action, idempotency_key)
) ENGINE=InnoDB;
