-- Track the built-in policy version without overwriting existing role grants.
SET @storagehub_policy_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'roles'
      AND column_name = 'permissions_policy_version'
);
SET @storagehub_add_policy_column = IF(
    @storagehub_policy_column_exists = 0,
    'ALTER TABLE roles ADD COLUMN permissions_policy_version INT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_policy_column;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;
