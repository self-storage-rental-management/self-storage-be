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
