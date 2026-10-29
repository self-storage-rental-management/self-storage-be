-- Persist the customer acceptance evidence and the immutable notice rule.
-- Existing reservations intentionally keep an empty tier snapshot; they are
-- routed to Business review instead of being recalculated from a newer policy.

ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN terms_snapshot_json LONGTEXT NULL;
ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN terms_acceptance_hash VARCHAR(64) NULL;
ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN terms_accepted_at TIMESTAMP(6) NULL;
ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN terms_accepted_by BINARY(16) NULL;
ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN early_termination_notice_basis VARCHAR(80) NULL;

UPDATE reservation_pricing_snapshots
SET early_termination_notice_basis = 'REQUESTED_AT_TO_SCHEDULED_DATE'
WHERE early_termination_notice_basis IS NULL;

ALTER TABLE return_cases
    ADD COLUMN legacy_pricing_review_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE refunds
    ADD COLUMN approved_by BINARY(16) NULL;
ALTER TABLE refunds
    ADD COLUMN approved_at TIMESTAMP(6) NULL;
ALTER TABLE refunds
    ADD COLUMN gateway_response_code VARCHAR(16) NULL;
ALTER TABLE refunds
    ADD COLUMN gateway_transaction_no VARCHAR(100) NULL;
