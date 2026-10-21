-- Existing settlements and actual receipts are deliberately not rewritten.
CREATE TABLE recovery_overdue_fee_assessments (
    return_id VARCHAR(36) NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    overdue_days BIGINT NOT NULL,
    accrued_amount DECIMAL(20,0) NOT NULL,
    outstanding_amount DECIMAL(20,0) NOT NULL,
    unposted_amount DECIMAL(20,0) NOT NULL,
    source_ref VARCHAR(200) NOT NULL,
    calculated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (return_id, snapshot_revision)
) ENGINE=InnoDB;
