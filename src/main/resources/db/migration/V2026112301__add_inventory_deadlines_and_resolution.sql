ALTER TABLE rentals
    ADD COLUMN inventory_final_deadline_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_deadline_reminder_at TIMESTAMP(6) NULL;

ALTER TABLE maintenance_tasks
    ADD COLUMN deadline_at TIMESTAMP(6) NULL;

ALTER TABLE check_ins
    ADD COLUMN inventory_resolution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN inventory_actual_json LONGTEXT NULL,
    ADD COLUMN inventory_mismatch_json LONGTEXT NULL,
    ADD COLUMN inventory_suggested_quantities_json LONGTEXT NULL,
    ADD COLUMN inventory_accepted_quantities_json LONGTEXT NULL,
    ADD COLUMN inventory_decision_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_decision_note VARCHAR(2000) NULL,
    ADD COLUMN inventory_full_fit BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inventory_partial_fit_available BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inventory_suggested_package_count INT NULL,
    ADD COLUMN inventory_mismatch_summary VARCHAR(2000) NULL;

