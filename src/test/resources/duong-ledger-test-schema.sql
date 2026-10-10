-- TEST DATABASE ONLY. No Flyway migration and no automatic runtime initialization.
CREATE TABLE duong_ledger_accounts (
 rental_id VARCHAR(36) PRIMARY KEY,
 customer_id VARCHAR(36) NOT NULL,
 facility_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL CHECK (revision >= 0)
);
CREATE TABLE duong_ledger_obligations (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 renewal_id VARCHAR(36),
 kind VARCHAR(32) NOT NULL CHECK (kind='RENT' OR kind='SECURITY_DEPOSIT' OR kind='RENEWAL_DEPOSIT' OR kind='RENEWAL_REMAINDER'),
 amount DECIMAL(19,0) NOT NULL CHECK (amount >= 0),
 due_at_ms BIGINT NOT NULL,
 source_ref VARCHAR(200) NOT NULL,
 UNIQUE (rental_id,source_ref,kind),
 UNIQUE (id,rental_id),
 FOREIGN KEY (rental_id) REFERENCES duong_ledger_accounts(rental_id)
);
CREATE INDEX idx_duong_ledger_due ON duong_ledger_obligations(rental_id,due_at_ms);
CREATE TABLE duong_ledger_receipts (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 method VARCHAR(32) NOT NULL CHECK (method='CASH' OR method='VERIFIED_BANK' OR method='SIMULATED'),
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 evidence_ref VARCHAR(200) NOT NULL UNIQUE,
 evidence_file_id VARCHAR(36) NOT NULL,
 actor_id VARCHAR(36) NOT NULL,
 received_at_ms BIGINT NOT NULL,
 UNIQUE (id,rental_id),
 FOREIGN KEY (rental_id) REFERENCES duong_ledger_accounts(rental_id)
);
CREATE INDEX idx_duong_ledger_receipt ON duong_ledger_receipts(rental_id,received_at_ms);
CREATE TABLE duong_ledger_allocations (
 receipt_id VARCHAR(36) NOT NULL,
 obligation_id VARCHAR(36) NOT NULL,
 rental_id VARCHAR(36) NOT NULL,
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 PRIMARY KEY (receipt_id,obligation_id),
 FOREIGN KEY (receipt_id,rental_id) REFERENCES duong_ledger_receipts(id,rental_id),
 FOREIGN KEY (obligation_id,rental_id) REFERENCES duong_ledger_obligations(id,rental_id)
);
CREATE INDEX idx_duong_ledger_allocation ON duong_ledger_allocations(obligation_id);
CREATE TABLE duong_ledger_refunds (
 id VARCHAR(36) PRIMARY KEY,
 rental_id VARCHAR(36) NOT NULL,
 receipt_id VARCHAR(36) NOT NULL,
 obligation_id VARCHAR(36) NOT NULL,
 amount DECIMAL(19,0) NOT NULL CHECK (amount > 0),
 status VARCHAR(16) NOT NULL CHECK (status='RESERVED' OR status='EXECUTED'),
 decision_ref VARCHAR(200) NOT NULL UNIQUE,
 payout_ref VARCHAR(200) UNIQUE,
 FOREIGN KEY (receipt_id,rental_id) REFERENCES duong_ledger_receipts(id,rental_id),
 FOREIGN KEY (obligation_id,rental_id) REFERENCES duong_ledger_obligations(id,rental_id),
 FOREIGN KEY (receipt_id,obligation_id) REFERENCES duong_ledger_allocations(receipt_id,obligation_id)
);
CREATE INDEX idx_duong_ledger_refund ON duong_ledger_refunds(receipt_id,obligation_id,status);
CREATE TABLE duong_ledger_commands (
 actor_id VARCHAR(36) NOT NULL,
 operation VARCHAR(32) NOT NULL,
 key_hash VARCHAR(64) NOT NULL,
 rental_id VARCHAR(36) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 result_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (actor_id,operation,key_hash),
 FOREIGN KEY (rental_id) REFERENCES duong_ledger_accounts(rental_id)
);
