SET @storagehub_roles_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'roles'
);

SET @storagehub_add_parent_role = IF(
    @storagehub_roles_exists = 1 AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'roles'
          AND column_name = 'parent_role_id'
    ),
    'ALTER TABLE roles ADD COLUMN parent_role_id BINARY(16) NULL',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_add_parent_role;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_parent_fk = IF(
    @storagehub_roles_exists = 1 AND NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'roles'
          AND constraint_name = 'fk_roles_parent_role'
    ),
    'ALTER TABLE roles ADD CONSTRAINT fk_roles_parent_role FOREIGN KEY (parent_role_id) REFERENCES roles(id)',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_parent_fk;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_assign_manager_parent = IF(
    @storagehub_roles_exists = 1,
    'UPDATE roles manager JOIN roles staff ON staff.code = ''STAFF'' SET manager.parent_role_id = staff.id WHERE manager.code = ''MANAGER''',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_assign_manager_parent;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_permissions_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'permissions'
);

SET @storagehub_normalize_permission_codes = IF(
    @storagehub_permissions_exists = 1,
    'UPDATE permissions SET code = CASE code
        WHEN ''view_dashboard'' THEN ''dashboard:read''
        WHEN ''view_facilities'' THEN ''facilities:read''
        WHEN ''view_units'' THEN ''storage_units:read''
        WHEN ''book_storage'' THEN ''reservations:create''
        WHEN ''view_reservations'' THEN ''reservations:read''
        WHEN ''approve_reservations'' THEN ''reservations:approve''
        WHEN ''assign_units'' THEN ''storage_units:assign''
        WHEN ''view_contracts'' THEN ''contracts:read''
        WHEN ''view_checkins'' THEN ''checkins:read''
        WHEN ''perform_checkin'' THEN ''checkins:process''
        WHEN ''view_rentals'' THEN ''rentals:read''
        WHEN ''manage_rentals'' THEN ''rentals:update''
        WHEN ''view_returns'' THEN ''returns:read''
        WHEN ''process_returns'' THEN ''returns:process''
        WHEN ''view_payments'' THEN ''payments:read''
        WHEN ''view_policies'' THEN ''policies:read''
        WHEN ''manage_payments'' THEN ''payments:collect''
        WHEN ''view_support'' THEN ''support:read''
        WHEN ''manage_support'' THEN ''support:update''
        WHEN ''manage_inventory'' THEN ''inventory:update''
        WHEN ''manage_policies'' THEN ''policies:update''
        WHEN ''manage_staff_tasks'' THEN ''staff_tasks:update''
        WHEN ''view_reports'' THEN ''reports:read''
        WHEN ''view_audit_logs'' THEN ''audit_logs:read''
        WHEN ''manage_users'' THEN ''users:manage''
        WHEN ''manage_roles'' THEN ''roles:manage''
        WHEN ''manage_settings'' THEN ''settings:manage''
        ELSE code
    END',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_normalize_permission_codes;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;
