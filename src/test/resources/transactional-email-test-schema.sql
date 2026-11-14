-- H2 test profile has Flyway disabled, so JDBC tables are outside Hibernate's schema.
CREATE TABLE IF NOT EXISTS payment_receipt_emails (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_key VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(36),
    recipient VARCHAR(320) NOT NULL,
    payload LONGTEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at TIMESTAMP(3),
    template_name VARCHAR(80) NOT NULL DEFAULT 'payment-bill',
    subject VARCHAR(255) NOT NULL,
    audit_action VARCHAR(100) NOT NULL,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at TIMESTAMP(3),
    locked_until TIMESTAMP(3),
    lock_token VARCHAR(36),
    last_error VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS request_rate_limits (
    key_hash VARCHAR(64) PRIMARY KEY,
    window_start BIGINT NOT NULL,
    request_count INT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL
);
