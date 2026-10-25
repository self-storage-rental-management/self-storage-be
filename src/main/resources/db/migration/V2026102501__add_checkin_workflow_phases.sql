SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'identity_verified_at') = 0, 'ALTER TABLE check_ins ADD COLUMN identity_verified_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'verified_identity_document') = 0, 'ALTER TABLE check_ins ADD COLUMN verified_identity_document VARCHAR(30) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'identity_verification_note') = 0, 'ALTER TABLE check_ins ADD COLUMN identity_verification_note VARCHAR(1000) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'contract_signed_at') = 0, 'ALTER TABLE check_ins ADD COLUMN contract_signed_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'contract_evidence_reference') = 0, 'ALTER TABLE check_ins ADD COLUMN contract_evidence_reference VARCHAR(36) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'payment_collected_at') = 0, 'ALTER TABLE check_ins ADD COLUMN payment_collected_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'setup_started_at') = 0, 'ALTER TABLE check_ins ADD COLUMN setup_started_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'setup_due_at') = 0, 'ALTER TABLE check_ins ADD COLUMN setup_due_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'setup_completed_at') = 0, 'ALTER TABLE check_ins ADD COLUMN setup_completed_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'inventory_verified_at') = 0, 'ALTER TABLE check_ins ADD COLUMN inventory_verified_at TIMESTAMP(6) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'lock_reference') = 0, 'ALTER TABLE check_ins ADD COLUMN lock_reference VARCHAR(160) NULL', 'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
