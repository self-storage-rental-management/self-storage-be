ALTER TABLE return_cases
    ADD COLUMN scheduled_appointment_at DATETIME(6) NULL,
    ADD COLUMN appointment_version INT NOT NULL DEFAULT 0,
    ADD COLUMN return_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_extension_idempotency_key VARCHAR(128) NULL,
    ADD COLUMN return_arrival_recorded_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_recorded_by BINARY(16) NULL,
    ADD COLUMN return_arrival_evidence_note VARCHAR(500) NULL,
    ADD COLUMN return_late_arrival_claimed_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_review_status VARCHAR(32) NULL,
    ADD COLUMN return_arrival_review_deadline_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_review_reason VARCHAR(1000) NULL,
    ADD COLUMN return_arrival_reviewed_by BINARY(16) NULL;
