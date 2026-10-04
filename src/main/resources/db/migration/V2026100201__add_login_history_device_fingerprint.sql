SET @storagehub_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'login_history'
      AND column_name = 'device_fingerprint'
);

SET @storagehub_add_column = IF(
    @storagehub_column_exists = 0,
    'ALTER TABLE login_history ADD COLUMN device_fingerprint VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_add_column;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'login_history'
      AND index_name = 'idx_login_history_user_fp'
);

SET @storagehub_add_index = IF(
    @storagehub_index_exists = 0,
    'CREATE INDEX idx_login_history_user_fp ON login_history (user_id, success, device_fingerprint)',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_add_index;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;
