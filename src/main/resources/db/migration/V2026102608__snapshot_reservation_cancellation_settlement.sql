ALTER TABLE reservations
    ADD COLUMN cancellation_type VARCHAR(40) NULL;
ALTER TABLE reservations
    ADD COLUMN cancellation_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00;
ALTER TABLE reservations
    ADD COLUMN cancellation_charge_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00;
