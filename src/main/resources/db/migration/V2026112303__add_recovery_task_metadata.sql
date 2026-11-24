ALTER TABLE maintenance_tasks
    ADD COLUMN recovery_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN recovery_handover_location VARCHAR(500) NULL;

ALTER TABLE check_ins
    ADD COLUMN inventory_remainder_removed_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_remainder_removal_note TEXT NULL,
    ADD COLUMN inventory_remainder_evidence_json LONGTEXT NULL;
