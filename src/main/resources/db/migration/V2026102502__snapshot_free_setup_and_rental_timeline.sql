SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_package_policies' AND column_name = 'free_setup_days') = 0, 'ALTER TABLE rental_package_policies ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE rental_package_policies
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 3
    WHEN 6 THEN 7
    WHEN 12 THEN 15
    WHEN 24 THEN 30
    ELSE 0
END;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'free_setup_days') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE reservation_quotes
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 3
    WHEN 6 THEN 7
    WHEN 12 THEN 15
    WHEN 24 THEN 30
    ELSE 0
END;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'setup_days_snapshot') = 0, 'ALTER TABLE reservations ADD COLUMN setup_days_snapshot INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'setup_goods_revision_used_at') = 0, 'ALTER TABLE reservations ADD COLUMN setup_goods_revision_used_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'free_setup_days') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN free_setup_days INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

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

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'setup_started_at') = 0, 'ALTER TABLE rentals ADD COLUMN setup_started_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'setup_ends_at') = 0, 'ALTER TABLE rentals ADD COLUMN setup_ends_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'terms_effective_at') = 0, 'ALTER TABLE rentals ADD COLUMN terms_effective_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'move_in_completed_at') = 0, 'ALTER TABLE rentals ADD COLUMN move_in_completed_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'inventory_verified_at') = 0, 'ALTER TABLE rentals ADD COLUMN inventory_verified_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'inventory_due_at') = 0, 'ALTER TABLE rentals ADD COLUMN inventory_due_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'inventory_pending') = 0, 'ALTER TABLE rentals ADD COLUMN inventory_pending BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'setup_exit_review_required') = 0, 'ALTER TABLE rentals ADD COLUMN setup_exit_review_required BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'setup_reminder_two_days_at') = 0, 'ALTER TABLE rentals ADD COLUMN setup_reminder_two_days_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'setup_reminder_last_day_at') = 0, 'ALTER TABLE rentals ADD COLUMN setup_reminder_last_day_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'inventory_escalated_at') = 0, 'ALTER TABLE rentals ADD COLUMN inventory_escalated_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rentals' AND column_name = 'inventory_task_created_at') = 0, 'ALTER TABLE rentals ADD COLUMN inventory_task_created_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'rentals' AND index_name = 'idx_rentals_setup_activation') = 0, 'CREATE INDEX idx_rentals_setup_activation ON rentals (status, setup_ends_at)', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
