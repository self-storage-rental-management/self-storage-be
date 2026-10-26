-- New accepted quotes only. NULL on historical terms deliberately requires manual reconciliation.
ALTER TABLE reservation_quotes
    ADD COLUMN setup_early_exit_policy_version VARCHAR(50) NULL,
    ADD COLUMN setup_early_exit_fee DECIMAL(14,2) NULL;
ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN setup_early_exit_policy_version VARCHAR(50) NULL,
    ADD COLUMN setup_early_exit_fee DECIMAL(14,2) NULL;
ALTER TABLE return_cases
    MODIFY COLUMN settlement_payment_id VARCHAR(120) NULL,
    ADD COLUMN setup_exit_requested BIT NOT NULL DEFAULT 0,
    ADD COLUMN setup_early_exit BIT NOT NULL DEFAULT 0,
    ADD COLUMN setup_returned_at DATETIME(6) NULL,
    ADD COLUMN setup_early_exit_policy_version VARCHAR(50) NULL,
    ADD COLUMN rental_refund_credit DECIMAL(14,2) NULL,
    ADD COLUMN early_termination_fee DECIMAL(14,2) NULL;
ALTER TABLE return_financial_snapshots
    ADD COLUMN setup_statement_json LONGTEXT NULL;
ALTER TABLE return_financial_snapshot_history
    ADD COLUMN setup_statement_json LONGTEXT NULL;

-- Classify only open historical requests; preserve all money/terms and completed history.
-- Missing old setup statements will stop completion and require reconciliation.
UPDATE return_cases rc JOIN rentals r ON r.id = rc.rental_id
SET rc.setup_exit_requested = 1
WHERE rc.status <> 'completed' AND r.setup_started_at IS NOT NULL AND r.setup_ends_at IS NOT NULL
  AND rc.requested_at >= r.setup_started_at AND rc.requested_at < r.setup_ends_at;
