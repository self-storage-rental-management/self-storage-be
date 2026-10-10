-- Preserve dimensions from databases created with implicit Hibernate names.

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'lengthm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'length_m'),
    'ALTER TABLE unit_types CHANGE COLUMN lengthm length_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'widthm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'width_m'),
    'ALTER TABLE unit_types CHANGE COLUMN widthm width_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'heightm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'height_m'),
    'ALTER TABLE unit_types CHANGE COLUMN heightm height_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_lengthm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_length_m'),
    'ALTER TABLE unit_types CHANGE COLUMN rack_lengthm rack_length_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_widthm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_width_m'),
    'ALTER TABLE unit_types CHANGE COLUMN rack_widthm rack_width_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_rename_dimension = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_heightm')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'unit_types' AND column_name = 'rack_height_m'),
    'ALTER TABLE unit_types CHANGE COLUMN rack_heightm rack_height_m DECIMAL(10,2) NOT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_dimension;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

