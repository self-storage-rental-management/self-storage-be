ALTER TABLE check_ins
    ADD COLUMN rejection_resolved_at TIMESTAMP(6) NULL,
    ADD COLUMN rejection_resolution_note VARCHAR(1000) NULL;
