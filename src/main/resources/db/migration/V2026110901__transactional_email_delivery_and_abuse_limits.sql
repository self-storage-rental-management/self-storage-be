ALTER TABLE payment_receipt_emails
    ADD COLUMN template_name VARCHAR(80) NOT NULL DEFAULT 'payment-bill',
    ADD COLUMN subject VARCHAR(255) NOT NULL DEFAULT 'StorageHub - Biên lai xác nhận thanh toán thành công',
    ADD COLUMN audit_action VARCHAR(100) NOT NULL DEFAULT 'PAYMENT_BILL_EMAIL_SENT',
    ADD COLUMN delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ADD COLUMN expires_at TIMESTAMP(3) NULL,
    ADD COLUMN locked_until TIMESTAMP(3) NULL,
    ADD COLUMN lock_token VARCHAR(36) NULL,
    ADD COLUMN last_error VARCHAR(64) NULL;
UPDATE payment_receipt_emails SET delivery_status = 'SENT' WHERE sent_at IS NOT NULL;
CREATE INDEX idx_transactional_mail_due ON payment_receipt_emails (delivery_status, next_attempt_at, locked_until);

CREATE TABLE request_rate_limits (
    key_hash VARCHAR(64) PRIMARY KEY,
    window_start BIGINT NOT NULL,
    request_count INT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    INDEX idx_rate_limit_expiry (expires_at)
);

ALTER TABLE return_cases ADD COLUMN refund_expected_at DATETIME(6) NULL;

-- Release legacy per-reservation verification only for already active accounts.
UPDATE reservations r JOIN users u ON u.id = r.customer_id
SET r.status = CASE WHEN r.goods_review_status = 'PENDING' THEN 'AWAITING_REVIEW' ELSE 'AWAITING_PAYMENT' END,
    r.goods_review_submitted_at = CASE WHEN r.goods_review_status = 'PENDING' THEN CURRENT_TIMESTAMP(3) ELSE r.goods_review_submitted_at END,
    r.goods_review_due_at = CASE WHEN r.goods_review_status = 'PENDING' THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 6 HOUR) ELSE r.goods_review_due_at END,
    r.payment_expires_at = CASE WHEN r.goods_review_status = 'PENDING' THEN NULL ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) END,
    r.hold_expires_at = CASE WHEN r.goods_review_status = 'PENDING' THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 6 HOUR) ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) END
WHERE r.status = 'AWAITING_EMAIL' AND r.hold_expires_at > CURRENT_TIMESTAMP(3) AND u.status = 'ACTIVE';
