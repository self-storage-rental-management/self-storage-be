ALTER TABLE activity_logs
 MODIFY COLUMN before_state_json LONGTEXT NULL,
 MODIFY COLUMN after_state_json LONGTEXT NULL;
