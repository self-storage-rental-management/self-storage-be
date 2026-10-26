ALTER TABLE payments
    MODIFY purpose ENUM(
        'DAMAGE_FEE',
        'EARLY_TERMINATION_FEE',
        'MONTHLY_RENT',
        'OTHER',
        'RENEWAL',
        'RENEWAL_INSTALLMENT',
        'RENTAL_BALANCE',
        'RESERVATION_DEPOSIT'
    ) NOT NULL;

ALTER TABLE return_cases
    ADD COLUMN return_type VARCHAR(32) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE return_cases
    ADD COLUMN settlement_mode VARCHAR(20) NOT NULL DEFAULT 'AUTO';
ALTER TABLE return_cases
    ADD COLUMN legacy_pricing_snapshot_json LONGTEXT NULL;
ALTER TABLE return_cases
    ADD COLUMN legacy_pricing_evidence_file_id BINARY(16) NULL;
ALTER TABLE return_cases
    ADD COLUMN legacy_pricing_decision_reason VARCHAR(2000) NULL;

ALTER TABLE rentals
    ADD COLUMN end_reason VARCHAR(32) NULL;

CREATE TABLE refund_requests (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    payment_id BINARY(16) NOT NULL,
    return_case_id BINARY(16) NULL,
    requested_by BINARY(16) NOT NULL,
    approved_by BINARY(16) NULL,
    amount DECIMAL(14,2) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    approved_at DATETIME(6) NULL,
    rejected_at DATETIME(6) NULL,
    processed_at DATETIME(6) NULL,
    rejection_reason VARCHAR(1000) NULL,
    gateway_refund_id VARCHAR(100) NULL,
    gateway_transaction_no VARCHAR(100) NULL,
    gateway_response_code VARCHAR(16) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_requests_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_refund_requests_return_case FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_refund_requests_requested_by FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT fk_refund_requests_approved_by FOREIGN KEY (approved_by) REFERENCES users(id),
    INDEX idx_refund_requests_status_created (status, created_at),
    INDEX idx_refund_requests_payment (payment_id)
);

UPDATE return_cases rc
JOIN rentals r ON r.id = rc.rental_id
JOIN reservation_pricing_snapshots ps ON ps.reservation_id = r.reservation_id
SET rc.legacy_pricing_review_required = TRUE,
    rc.settlement_mode = 'MANUAL_REVIEW',
    rc.status = 'awaiting_business_review'
WHERE (ps.early_termination_pricing_snapshot_json IS NULL
    OR TRIM(ps.early_termination_pricing_snapshot_json) IN ('', '[]'))
  AND rc.active_return_repricing_required = TRUE
  AND rc.status <> 'completed';
