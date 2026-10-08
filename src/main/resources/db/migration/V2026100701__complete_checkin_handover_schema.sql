ALTER TABLE check_ins
    ADD COLUMN scheduled_at DATETIME(6) NULL,
    ADD COLUMN readiness_note VARCHAR(1000) NULL,
    ADD COLUMN rejection_reason VARCHAR(1000) NULL,
    ADD COLUMN rejection_disposition VARCHAR(20) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE check_ins
    MODIFY COLUMN checklist_json VARCHAR(8000) NULL;

ALTER TABLE check_ins
    ADD CONSTRAINT uk_check_in_reservation UNIQUE (reservation_id);
