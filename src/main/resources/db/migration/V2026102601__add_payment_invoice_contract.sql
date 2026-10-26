-- Persist invoice metadata alongside the payment ledger. Existing payment
-- status and refund evidence are preserved; only deterministic invoice state
-- is backfilled for already-known rows.
ALTER TABLE payments
    ADD COLUMN invoice_status VARCHAR(16) NULL,
    ADD COLUMN invoice_number VARCHAR(64) NULL,
    ADD COLUMN invoice_issued_at TIMESTAMP(6) NULL;

UPDATE payments
SET invoice_status = CASE
    WHEN refunded_amount > 0 THEN 'REFUNDED'
    WHEN status = 'PAID' THEN 'PAID'
    WHEN status IN ('FAILED', 'CANCELLED') THEN 'VOID'
    ELSE 'DRAFT'
END;

UPDATE payments
SET invoice_number = CONCAT('INV-', UPPER(REPLACE(UUID(), '-', ''))),
    invoice_issued_at = COALESCE(paid_at, processed_at, created_at)
WHERE invoice_status IN ('PAID', 'REFUNDED');

ALTER TABLE payments
    MODIFY COLUMN invoice_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    ADD CONSTRAINT uk_payments_invoice_number UNIQUE (invoice_number);
