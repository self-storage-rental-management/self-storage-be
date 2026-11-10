CREATE TABLE registration_captchas (
    id VARCHAR(36) PRIMARY KEY,
    answer_hash VARCHAR(64) NOT NULL,
    client_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    INDEX idx_captcha_client_created (client_hash, created_at),
    INDEX idx_captcha_expiry (expires_at)
);

CREATE TABLE payment_receipt_emails (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_key VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(36),
    recipient VARCHAR(320) NOT NULL,
    payload LONGTEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at TIMESTAMP(3),
    INDEX idx_receipt_email_pending (sent_at, next_attempt_at)
);
