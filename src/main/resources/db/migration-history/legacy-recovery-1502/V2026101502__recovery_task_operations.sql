CREATE TABLE recovery_task_operations (
    task_id BINARY(16) NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    started_by BINARY(16) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    return_case_id BINARY(16) NULL UNIQUE,
    inspection_json LONGTEXT NULL,
    assets_cleared_at TIMESTAMP(6) NULL,
    completion_file_id BINARY(16) NULL,
    completed_by BINARY(16) NULL,
    completed_at TIMESTAMP(6) NULL,
    completion_note VARCHAR(2000) NULL,
    CONSTRAINT fk_recovery_operation_task FOREIGN KEY (task_id) REFERENCES recovery_tasks(id),
    CONSTRAINT fk_recovery_operation_return FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_recovery_operation_actor FOREIGN KEY (started_by) REFERENCES users(id),
    CONSTRAINT fk_recovery_operation_file FOREIGN KEY (completion_file_id) REFERENCES file_assets(id)
) ENGINE=InnoDB;
CREATE TABLE recovery_task_commands (
    actor_id BINARY(16) NOT NULL,
    key_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id BINARY(16) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json LONGTEXT NOT NULL,
    PRIMARY KEY (actor_id,key_hash),
    CONSTRAINT fk_recovery_command_task FOREIGN KEY (task_id) REFERENCES recovery_tasks(id)
) ENGINE=InnoDB;
CREATE TABLE recovery_settlement_receipts (
    task_id BINARY(16) NOT NULL PRIMARY KEY,
    return_case_id BINARY(16) NOT NULL UNIQUE,
    direction VARCHAR(16) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    payment_method VARCHAR(24) NOT NULL,
    transaction_reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    receipt_file_id BINARY(16) NOT NULL UNIQUE,
    recorded_by BINARY(16) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_recovery_receipt_task FOREIGN KEY (task_id) REFERENCES recovery_tasks(id),
    CONSTRAINT fk_recovery_receipt_return FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_recovery_receipt_file FOREIGN KEY (receipt_file_id) REFERENCES file_assets(id),
    CHECK (amount > 0),
    CHECK (direction IN ('COLLECTION','REFUND')),
    CHECK (payment_method IN ('CASH','BANK_TRANSFER'))
) ENGINE=InnoDB;
