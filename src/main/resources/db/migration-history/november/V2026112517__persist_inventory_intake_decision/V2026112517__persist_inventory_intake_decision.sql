-- Idempotent for local environments that applied the additive column before restarting.
SET @inventory_decision_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'check_ins' AND column_name = 'inventory_decision_json') = 0,
    'ALTER TABLE check_ins ADD COLUMN inventory_decision_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE inventory_decision_statement FROM @inventory_decision_ddl;
EXECUTE inventory_decision_statement;
DEALLOCATE PREPARE inventory_decision_statement;
