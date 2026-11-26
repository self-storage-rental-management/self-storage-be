ALTER TABLE return_cases
    ADD COLUMN return_arrived_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_evidence_id BINARY(16) NULL,
    ADD COLUMN return_first_attempt_id BINARY(16) NULL,
    ADD COLUMN return_second_attempt_id BINARY(16) NULL,
    ADD COLUMN return_second_attempt_at DATETIME(6) NULL,
    ADD COLUMN return_identity_verified_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_reviewed_at DATETIME(6) NULL,
    ADD COLUMN return_arrival_review_accepted BOOLEAN NULL;
