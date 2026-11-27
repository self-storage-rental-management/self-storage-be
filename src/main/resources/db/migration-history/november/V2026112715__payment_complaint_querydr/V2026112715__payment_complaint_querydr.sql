ALTER TABLE payment_complaints
    ADD COLUMN gateway_query_checked_at TIMESTAMP(6) NULL,
    ADD COLUMN gateway_query_response_code VARCHAR(16) NULL,
    ADD COLUMN gateway_query_transaction_status VARCHAR(16) NULL,
    ADD COLUMN gateway_query_transaction_no VARCHAR(64) NULL,
    ADD COLUMN gateway_query_pay_date VARCHAR(32) NULL,
    ADD COLUMN gateway_query_conclusion VARCHAR(40) NULL,
    ADD COLUMN gateway_query_message VARCHAR(500) NULL;
