ALTER TABLE return_cases
    ADD COLUMN settlement_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN settlement_published_at DATETIME(6) NULL,
    ADD COLUMN settlement_payment_due_at DATETIME(6) NULL,
    ADD COLUMN settlement_ledger_revision BIGINT NULL;

ALTER TABLE return_settlement_receipts
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (return_id, direction);
