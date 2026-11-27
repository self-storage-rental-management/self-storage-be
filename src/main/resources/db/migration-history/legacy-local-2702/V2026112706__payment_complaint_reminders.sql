ALTER TABLE payment_complaints
    ADD COLUMN last_reminder_at TIMESTAMP(6) NULL,
    ADD COLUMN last_reminder_stage VARCHAR(32) NULL;
