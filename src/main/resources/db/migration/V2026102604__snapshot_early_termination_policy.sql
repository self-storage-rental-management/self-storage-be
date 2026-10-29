SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_package_policies' AND column_name = 'early_termination_fee_cap_months') = 0, 'ALTER TABLE rental_package_policies ADD COLUMN early_termination_fee_cap_months INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_package_policies' AND column_name = 'early_termination_fee_coefficient') = 0, 'ALTER TABLE rental_package_policies ADD COLUMN early_termination_fee_coefficient DECIMAL(5,4) NOT NULL DEFAULT 1.0000', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_package_policies' AND column_name = 'early_termination_notice_days') = 0, 'ALTER TABLE rental_package_policies ADD COLUMN early_termination_notice_days INT NOT NULL DEFAULT 14', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'early_termination_fee_cap_months') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN early_termination_fee_cap_months INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'early_termination_fee_coefficient') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN early_termination_fee_coefficient DECIMAL(5,4) NOT NULL DEFAULT 1.0000', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'early_termination_notice_days') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN early_termination_notice_days INT NOT NULL DEFAULT 14', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'early_termination_fee') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN early_termination_fee DECIMAL(14,2) NOT NULL DEFAULT 0.00', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'early_termination_fee_cap_months') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN early_termination_fee_cap_months INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'early_termination_fee_coefficient') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN early_termination_fee_coefficient DECIMAL(5,4) NOT NULL DEFAULT 1.0000', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'early_termination_notice_days') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN early_termination_notice_days INT NOT NULL DEFAULT 14', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots' AND column_name = 'early_termination_fee') = 0, 'ALTER TABLE reservation_pricing_snapshots ADD COLUMN early_termination_fee DECIMAL(14,2) NOT NULL DEFAULT 0.00', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE reservation_quotes q
SET q.early_termination_fee = LEAST(
    q.reservation_deposit_amount,
    ROUND((q.total_after_discount / NULLIF(q.rental_months, 0)) * q.early_termination_fee_cap_months * q.early_termination_fee_coefficient, 2)
);

UPDATE reservation_pricing_snapshots s
SET s.early_termination_fee = LEAST(
    s.reservation_deposit_amount,
    ROUND((s.net_rental_amount / NULLIF(s.rental_months, 0)) * s.early_termination_fee_cap_months * s.early_termination_fee_coefficient, 2)
);
