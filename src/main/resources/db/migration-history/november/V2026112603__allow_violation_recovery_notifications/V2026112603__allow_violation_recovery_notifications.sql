SET @migration_sql = (
  SELECT IF(DATA_TYPE = 'enum' AND LOCATE('VIOLATION_RECOVERY', COLUMN_TYPE) = 0,
    'ALTER TABLE notifications MODIFY COLUMN type ENUM(''CHECKIN'',''OVERDUE'',''PAYMENT'',''RENEWAL'',''RESERVATION'',''RETURN'',''SUPPORT'',''SYSTEM'',''VIOLATION_RECOVERY'') NOT NULL',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications' AND COLUMN_NAME = 'type'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
