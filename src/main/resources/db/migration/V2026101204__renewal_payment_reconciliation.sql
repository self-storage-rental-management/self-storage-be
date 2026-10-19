-- Additive only. Payment is the durable reconciliation queue; no historical rows are rewritten.
ALTER TABLE payments ADD COLUMN renewal_evidence_ref BINARY(16) NULL;
ALTER TABLE payments ADD COLUMN renewal_ledger_receipt_ref BINARY(16) NULL;
ALTER TABLE payments ADD COLUMN renewal_allocated_at TIMESTAMP(6) NULL;
ALTER TABLE payments ADD COLUMN renewal_signing_prepared_at TIMESTAMP(6) NULL;
ALTER TABLE payments ADD COLUMN renewal_next_retry_at TIMESTAMP(6) NULL;
ALTER TABLE payments ADD COLUMN renewal_reconciliation_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD CONSTRAINT fk_payment_renewal_evidence FOREIGN KEY (renewal_evidence_ref) REFERENCES activity_logs(id);
ALTER TABLE payments ADD CONSTRAINT uk_payment_renewal_evidence UNIQUE (renewal_evidence_ref);
ALTER TABLE payments ADD CONSTRAINT uk_payment_renewal_receipt UNIQUE (renewal_ledger_receipt_ref);
ALTER TABLE renewal_accepted_revisions ADD CONSTRAINT uk_accepted_revision_resource UNIQUE (id,renewal_id);
ALTER TABLE payments ADD CONSTRAINT fk_payment_accepted_revision_resource
    FOREIGN KEY (renewal_revision_id,renewal_id) REFERENCES renewal_accepted_revisions(id,renewal_id);
CREATE INDEX idx_payment_renewal_reconciliation ON payments(status, renewal_next_retry_at);
