-- Isolated TEST schema for the rental subledger. Not a runtime initializer.
CREATE TABLE rental_ledger_accounts (
 rental_id VARCHAR(36) PRIMARY KEY,
 customer_id VARCHAR(36) NOT NULL,
 facility_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL CHECK (revision >= 0)
);
CREATE TABLE rental_ledger_obligations (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 renewal_id VARCHAR(36),
 kind VARCHAR(32) NOT NULL CHECK (kind='RENT' OR kind='SECURITY_DEPOSIT' OR kind='RENEWAL_DEPOSIT' OR kind='RENEWAL_REMAINDER'),
 amount DECIMAL(19,0) NOT NULL CHECK (amount >= 0),
 due_at_ms BIGINT NOT NULL,
 source_ref VARCHAR(200) NOT NULL,
 UNIQUE (rental_id,source_ref,kind),
 UNIQUE (id,rental_id),
 FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id)
);
CREATE INDEX idx_rental_ledger_due ON rental_ledger_obligations(rental_id,due_at_ms);
CREATE TABLE renewal_ledger_cancellation_credits (
 id VARCHAR(36) PRIMARY KEY,rental_id VARCHAR(36) NOT NULL,obligation_id VARCHAR(36) NOT NULL,
 amount DECIMAL(19,0) NOT NULL CHECK(amount>0),source_ref VARCHAR(200) NOT NULL,reason VARCHAR(200) NOT NULL,
 UNIQUE(rental_id,source_ref),FOREIGN KEY(obligation_id,rental_id) REFERENCES rental_ledger_obligations(id,rental_id)
);
CREATE TABLE rental_ledger_receipts (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 method VARCHAR(32) NOT NULL CHECK (method='CASH' OR method='VERIFIED_BANK' OR method='SIMULATED' OR method='VNPAY_SANDBOX'),
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 evidence_ref VARCHAR(200) NOT NULL UNIQUE,
 evidence_file_id VARCHAR(36),
 actor_id VARCHAR(36) NOT NULL,
 received_at_ms BIGINT NOT NULL,
 UNIQUE (id,rental_id),
 FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id)
);
CREATE INDEX idx_rental_ledger_receipt ON rental_ledger_receipts(rental_id,received_at_ms);
CREATE TABLE rental_ledger_allocations (
 receipt_id VARCHAR(36) NOT NULL,
 obligation_id VARCHAR(36) NOT NULL,
 rental_id VARCHAR(36) NOT NULL,
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 PRIMARY KEY (receipt_id,obligation_id),
 FOREIGN KEY (receipt_id,rental_id) REFERENCES rental_ledger_receipts(id,rental_id),
 FOREIGN KEY (obligation_id,rental_id) REFERENCES rental_ledger_obligations(id,rental_id)
);
CREATE INDEX idx_rental_ledger_allocation ON rental_ledger_allocations(obligation_id);
CREATE TABLE rental_ledger_refunds (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 receipt_id VARCHAR(36) NOT NULL,
 obligation_id VARCHAR(36) NOT NULL,
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 status VARCHAR(16) NOT NULL CHECK (status='RESERVED' OR status='EXECUTED'),
 decision_ref VARCHAR(200) NOT NULL UNIQUE,
 payout_ref VARCHAR(200) UNIQUE,
 FOREIGN KEY (receipt_id,rental_id) REFERENCES rental_ledger_receipts(id,rental_id),
 FOREIGN KEY (obligation_id,rental_id) REFERENCES rental_ledger_obligations(id,rental_id),
 FOREIGN KEY (receipt_id,obligation_id) REFERENCES rental_ledger_allocations(receipt_id,obligation_id)
);
CREATE INDEX idx_rental_ledger_refund ON rental_ledger_refunds(receipt_id,obligation_id,status);
CREATE TABLE rental_ledger_commands (
 actor_id VARCHAR(36) NOT NULL,
 operation VARCHAR(32) NOT NULL,
 key_hash VARCHAR(64) NOT NULL,
 rental_id VARCHAR(36) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 result_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (actor_id,operation,key_hash),
 FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id)
);

CREATE TABLE rental_ledger_gateway_evidence (receipt_id VARCHAR(36) PRIMARY KEY,payment_id VARCHAR(36) NOT NULL UNIQUE,proof_log_id VARCHAR(36) NOT NULL UNIQUE,environment VARCHAR(16) NOT NULL CHECK(environment='SANDBOX'),transaction_no VARCHAR(128) NOT NULL UNIQUE,FOREIGN KEY(receipt_id) REFERENCES rental_ledger_receipts(id));

-- Match the due-date and return-finance tables used by the production ledger.
CREATE TABLE rental_ledger_due_changes (
    sequence_no BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    obligation_id VARCHAR(36) NOT NULL,
    old_due_at_ms BIGINT NOT NULL,
    due_at_ms BIGINT NOT NULL,
    actor_id VARCHAR(36) NULL,
    decision_ref VARCHAR(128) NOT NULL,
    recorded_at_ms BIGINT NOT NULL,
    CONSTRAINT uk_due_change UNIQUE (obligation_id,decision_ref)
);
CREATE INDEX idx_due_obligation ON rental_ledger_due_changes(obligation_id,sequence_no);

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
    recorded_by VARCHAR(36) NULL
);
CREATE INDEX idx_return_finance_rental ON return_financial_snapshots(rental_id);
CREATE TABLE return_ledger_allocations (
    return_id VARCHAR(36) NOT NULL,
    obligation_id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    PRIMARY KEY(return_id,obligation_id)
);
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
    CONSTRAINT uk_return_financial_history UNIQUE (return_id,revision)
);
CREATE TABLE return_settlement_receipts (
    return_id VARCHAR(36) NOT NULL PRIMARY KEY,
    direction VARCHAR(16) NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    payment_method VARCHAR(24) NOT NULL,
    transaction_reference VARCHAR(120) NOT NULL UNIQUE,
    receipt_file_id VARCHAR(36) NOT NULL UNIQUE,
    recorded_by VARCHAR(36) NOT NULL,
    recorded_at_ms BIGINT NOT NULL
);

-- JDBC-owned tables used by real services in full Spring test contexts.
CREATE TABLE business_record_code_sequences (
 type_code CHAR(2) NOT NULL,
 period CHAR(4) NOT NULL,
 serial_value BIGINT NOT NULL CHECK(serial_value BETWEEN 1 AND 1000000),
 PRIMARY KEY(type_code,period)
);
CREATE TABLE business_record_codes (
 resource_key VARCHAR(120) PRIMARY KEY,
 type_code CHAR(2) NOT NULL,
 period CHAR(4) NOT NULL,
 display_code VARCHAR(16) NOT NULL UNIQUE,
 recorded_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE confirmed_rental_periods (
 sequence_no BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 reservation_id VARCHAR(36) NOT NULL,
 customer_id VARCHAR(36) NOT NULL,
 facility_id VARCHAR(36) NOT NULL,
 storage_unit_id VARCHAR(36) NOT NULL,
 start_date DATE NOT NULL,
 stored_end_date DATE NOT NULL,
 end_convention VARCHAR(12) NOT NULL,
 source_ref VARCHAR(200) NOT NULL,
 confirmed_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uk_confirmed_period_source UNIQUE(rental_id,source_ref)
);
CREATE INDEX idx_confirmed_period_current ON confirmed_rental_periods(rental_id,sequence_no);

CREATE TABLE recovery_overdue_fee_assessments (
 return_id VARCHAR(36) NOT NULL,
 snapshot_revision BIGINT NOT NULL,
 overdue_days BIGINT NOT NULL,
 accrued_amount DECIMAL(20,0) NOT NULL,
 outstanding_amount DECIMAL(20,0) NOT NULL,
 unposted_amount DECIMAL(20,0) NOT NULL,
 source_ref VARCHAR(200) NOT NULL,
 calculated_at_ms BIGINT NOT NULL,
 PRIMARY KEY(return_id,snapshot_revision)
);
