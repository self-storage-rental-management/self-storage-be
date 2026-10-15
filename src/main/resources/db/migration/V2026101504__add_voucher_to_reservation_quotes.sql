-- Add voucher columns to reservation_quotes for full quotation traceability
ALTER TABLE reservation_quotes
    ADD COLUMN voucher_code VARCHAR(50) NULL,
    ADD COLUMN voucher_discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00;
