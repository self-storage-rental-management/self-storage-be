SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rental_package_policies' AND column_name = 'deposit_rate') = 0, 'ALTER TABLE rental_package_policies ADD COLUMN deposit_rate DECIMAL(5,4) NOT NULL DEFAULT 0.2000', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes' AND column_name = 'reservation_deposit_rate') = 0, 'ALTER TABLE reservation_quotes ADD COLUMN reservation_deposit_rate DECIMAL(5,4) NOT NULL DEFAULT 0.2000', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
