-- Recovery handoff creates a durable work item. Physical repossession remains a separate action.
CREATE TABLE recovery_tasks (
    id BINARY(16) NOT NULL,
    case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recovery_task_case_ref UNIQUE (case_ref),
    CONSTRAINT uk_recovery_task_rental UNIQUE (rental_id),
    CONSTRAINT fk_recovery_task_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_recovery_task_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT ck_recovery_task_status CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED'))
) ENGINE=InnoDB;
CREATE INDEX ix_recovery_task_facility_status ON recovery_tasks (facility_id, status, received_at);
