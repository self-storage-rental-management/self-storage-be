ALTER TABLE maintenance_tasks
    ADD COLUMN recovery_witness_id BINARY(16) NULL,
    ADD CONSTRAINT fk_maintenance_tasks_recovery_witness
        FOREIGN KEY (recovery_witness_id) REFERENCES users(id);
