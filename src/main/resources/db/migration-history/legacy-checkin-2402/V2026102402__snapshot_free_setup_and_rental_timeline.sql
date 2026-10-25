ALTER TABLE rental_package_policies
    ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0;

UPDATE rental_package_policies
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 3
    WHEN 6 THEN 7
    WHEN 12 THEN 15
    WHEN 24 THEN 30
    ELSE 0
END;

ALTER TABLE reservation_quotes
    ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0;

UPDATE reservation_quotes
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 3
    WHEN 6 THEN 7
    WHEN 12 THEN 15
    WHEN 24 THEN 30
    ELSE 0
END;

ALTER TABLE reservations
    ADD COLUMN setup_days_snapshot INT NOT NULL DEFAULT 0,
    ADD COLUMN setup_goods_revision_used_at TIMESTAMP(6) NULL;

ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0;

UPDATE reservation_pricing_snapshots
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 3
    WHEN 6 THEN 7
    WHEN 12 THEN 15
    WHEN 24 THEN 30
    ELSE 0
END;

UPDATE reservations r
JOIN reservation_pricing_snapshots s ON s.reservation_id = r.id
SET r.setup_days_snapshot = s.free_setup_days;

ALTER TABLE rentals
    ADD COLUMN setup_started_at TIMESTAMP(6) NULL,
    ADD COLUMN setup_ends_at TIMESTAMP(6) NULL,
    ADD COLUMN terms_effective_at TIMESTAMP(6) NULL,
    ADD COLUMN move_in_completed_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_verified_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_due_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN setup_exit_review_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN setup_reminder_two_days_at TIMESTAMP(6) NULL,
    ADD COLUMN setup_reminder_last_day_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_escalated_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_task_created_at TIMESTAMP(6) NULL;

CREATE INDEX idx_rentals_setup_activation ON rentals (status, setup_ends_at);
