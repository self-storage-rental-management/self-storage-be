SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'early_termination_pricing_snapshot_json') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN early_termination_pricing_snapshot_json LONGTEXT NULL', 'SELECT 1');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'early_termination_pricing_snapshot_json') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN early_termination_pricing_snapshot_json LONGTEXT NULL', 'SELECT 1');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

UPDATE reservation_quotes
SET early_termination_pricing_snapshot_json = '[]'
WHERE early_termination_pricing_snapshot_json IS NULL OR early_termination_pricing_snapshot_json = '';

UPDATE reservation_pricing_snapshots
SET early_termination_pricing_snapshot_json = '[]'
WHERE early_termination_pricing_snapshot_json IS NULL OR early_termination_pricing_snapshot_json = '';

ALTER TABLE reservation_quotes
    MODIFY early_termination_pricing_snapshot_json LONGTEXT NOT NULL;

ALTER TABLE reservation_pricing_snapshots
    MODIFY early_termination_pricing_snapshot_json LONGTEXT NOT NULL;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'return_cases' AND column_name = 'active_return_repricing_required') = 0, 'ALTER TABLE return_cases ADD COLUMN active_return_repricing_required BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'return_cases' AND column_name = 'early_termination_charge') = 0, 'ALTER TABLE return_cases ADD COLUMN early_termination_charge DECIMAL(14,2) NOT NULL DEFAULT 0.00', 'SELECT 1');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'return_cases' AND column_name = 'rental_refund_amount') = 0, 'ALTER TABLE return_cases ADD COLUMN rental_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00', 'SELECT 1');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
