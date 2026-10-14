ALTER TABLE unit_types
    ADD COLUMN security_deposit_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 AFTER monthly_price;

UPDATE unit_types
SET security_deposit_amount = monthly_price
WHERE security_deposit_amount = 0.00;
