-- This migration can be reached after V2026112404 on databases that were
-- migrated with the inventory-draft branch first. Add each column only when
-- it is still missing so the earlier column does not make the whole migration
-- fail with "Duplicate column name".
SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'inventory_draft_json'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE check_ins ADD COLUMN inventory_draft_json LONGTEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'inventory_draft_saved_at'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE check_ins ADD COLUMN inventory_draft_saved_at TIMESTAMP(6) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'inventory_draft_revision'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE check_ins ADD COLUMN inventory_draft_revision BIGINT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
