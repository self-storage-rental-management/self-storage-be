ALTER TABLE payments
    ADD COLUMN gateway_provider VARCHAR(32) NULL,
    ADD COLUMN gateway_transaction_no VARCHAR(100) NULL,
    ADD COLUMN gateway_bank_code VARCHAR(32) NULL,
    ADD COLUMN gateway_card_type VARCHAR(32) NULL,
    ADD COLUMN gateway_response_code VARCHAR(16) NULL,
    ADD COLUMN gateway_transaction_status VARCHAR(16) NULL,
    ADD COLUMN gateway_pay_date VARCHAR(32) NULL,
    ADD COLUMN last_reconciled_at TIMESTAMP(6) NULL,
    ADD COLUMN refunded_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00;
