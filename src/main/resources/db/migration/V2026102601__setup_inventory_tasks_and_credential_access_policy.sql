SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'maintenance_tasks' AND column_name = 'task_type') = 0,
    'ALTER TABLE maintenance_tasks ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT ''maintenance''',
    'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'maintenance_tasks' AND column_name = 'rental_id') = 0,
    'ALTER TABLE maintenance_tasks ADD COLUMN rental_id BINARY(16) NULL',
    'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'maintenance_tasks' AND index_name = 'idx_maintenance_tasks_rental') = 0,
    'CREATE INDEX idx_maintenance_tasks_rental ON maintenance_tasks (rental_id)',
    'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND table_name = 'maintenance_tasks' AND constraint_name = 'fk_maintenance_tasks_rental') = 0,
    'ALTER TABLE maintenance_tasks ADD CONSTRAINT fk_maintenance_tasks_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)',
    'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF((SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'maintenance_tasks' AND column_name = 'reported_by') = 'NO',
    'ALTER TABLE maintenance_tasks MODIFY COLUMN reported_by BINARY(16) NULL',
    'SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
