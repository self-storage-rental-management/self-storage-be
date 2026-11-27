ALTER TABLE recovery_tasks
    ADD COLUMN assigned_staff_id BINARY(16) NULL,
    ADD CONSTRAINT fk_recovery_task_assigned_staff FOREIGN KEY (assigned_staff_id) REFERENCES users(id);
