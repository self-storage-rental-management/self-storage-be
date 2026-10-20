ALTER TABLE renewal_workflows ADD COLUMN signing_balance_due_at TIMESTAMP(6) NULL;
UPDATE renewal_workflows w JOIN renewal_operation_states s ON s.id=w.id JOIN renewals n ON n.id=w.id
SET w.signing_balance_due_at=CASE WHEN n.status IN ('completed','cancelled','rejected','payment_expired')
    THEN w.payment_deadline ELSE s.appointment_end END
WHERE s.appointment_end IS NOT NULL;

CREATE TABLE rental_ledger_due_changes (
    sequence_no BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    obligation_id VARCHAR(36) NOT NULL,
    old_due_at_ms BIGINT NOT NULL,
    due_at_ms BIGINT NOT NULL,
    actor_id VARCHAR(36) NULL,
    decision_ref VARCHAR(128) NOT NULL,
    recorded_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_due_change (obligation_id,decision_ref),
    INDEX idx_due_obligation (obligation_id,sequence_no)
) ENGINE=InnoDB;

-- Keep original charge timestamps/command hashes; append corrected due dates for open balances.
SET @has_ledger = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('rental_ledger_obligations','rental_ledger_accounts'));
SET @backfill_sql = IF(@has_ledger=2,'INSERT INTO rental_ledger_due_changes(rental_id,obligation_id,old_due_at_ms,due_at_ms,actor_id,decision_ref,recorded_at_ms)
SELECT o.rental_id,o.id,o.due_at_ms,ROUND(UNIX_TIMESTAMP(w.signing_balance_due_at)*1000),NULL,
    ''SIGNING_BALANCE_DUE_BACKFILL_V1'',ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
FROM rental_ledger_obligations o
JOIN renewal_workflows w ON w.id=UNHEX(REPLACE(o.renewal_id,''-'',''''))
JOIN renewals n ON n.id=w.id
WHERE o.kind=''RENEWAL_REMAINDER'' AND o.source_ref LIKE ''%:REMAINDER''
AND o.source_ref LIKE CONCAT(''renewal-accepted:'',LOWER(BIN_TO_UUID(w.accepted_revision_id)), '':%'')
AND w.signing_balance_due_at IS NOT NULL
AND n.status NOT IN (''completed'',''cancelled'',''rejected'',''payment_expired'')
AND o.due_at_ms<>ROUND(UNIX_TIMESTAMP(w.signing_balance_due_at)*1000)','SELECT 1');
PREPARE due_backfill FROM @backfill_sql;
EXECUTE due_backfill;
DEALLOCATE PREPARE due_backfill;

SET @backfill_sql = IF(@has_ledger=2,'UPDATE rental_ledger_accounts a SET revision=revision+1
WHERE EXISTS(SELECT 1 FROM rental_ledger_due_changes d WHERE d.rental_id=a.rental_id)','SELECT 1');
PREPARE due_backfill FROM @backfill_sql;
EXECUTE due_backfill;
DEALLOCATE PREPARE due_backfill;

CREATE TABLE return_financial_snapshots (
    return_id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    ledger_revision BIGINT NOT NULL,
    security_payment_id VARCHAR(36) NOT NULL,
    deposit_amount DECIMAL(20,0) NOT NULL,
    debt_amount DECIMAL(20,0) NOT NULL,
    allocations_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL,
    captured_at_ms BIGINT NOT NULL,
    settled_at_ms BIGINT NULL,
    settlement_reference VARCHAR(128) NULL,
    recorded_by VARCHAR(36) NULL,
    INDEX idx_return_finance_rental (rental_id)
) ENGINE=InnoDB;
CREATE TABLE return_ledger_allocations (
    return_id VARCHAR(36) NOT NULL,
    obligation_id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    PRIMARY KEY(return_id,obligation_id)
) ENGINE=InnoDB;
CREATE TABLE return_financial_snapshot_history (
    sequence_no BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    return_id VARCHAR(36) NOT NULL,
    ledger_revision BIGINT NOT NULL,
    security_payment_id VARCHAR(36) NOT NULL,
    deposit_amount DECIMAL(20,0) NOT NULL,
    debt_amount DECIMAL(20,0) NOT NULL,
    allocations_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL,
    captured_at_ms BIGINT NOT NULL,
    UNIQUE KEY uk_return_financial_history (return_id,revision)
) ENGINE=InnoDB;
CREATE TABLE return_settlement_receipts (
    return_id VARCHAR(36) NOT NULL PRIMARY KEY,
    direction VARCHAR(16) NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    payment_method VARCHAR(24) NOT NULL,
    transaction_reference VARCHAR(120) NOT NULL UNIQUE,
    receipt_file_id VARCHAR(36) NOT NULL UNIQUE,
    recorded_by VARCHAR(36) NOT NULL,
    recorded_at_ms BIGINT NOT NULL
) ENGINE=InnoDB;
