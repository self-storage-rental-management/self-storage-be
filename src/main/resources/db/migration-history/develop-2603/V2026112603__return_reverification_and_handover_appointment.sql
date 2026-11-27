ALTER TABLE return_cases
    ADD COLUMN return_reverification_started_at DATETIME(6) NULL,
    ADD COLUMN return_reverification_staff_id BINARY(16) NULL,
    ADD COLUMN post_review_handover_appointment_at DATETIME(6) NULL;
