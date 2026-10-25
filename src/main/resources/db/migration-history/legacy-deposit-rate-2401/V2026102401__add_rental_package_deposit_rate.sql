-- Add deposit_rate to rental_package_policies and reservation_quotes
ALTER TABLE rental_package_policies
    ADD COLUMN deposit_rate DECIMAL(5,4) NOT NULL DEFAULT 0.2000;

ALTER TABLE reservation_quotes
    ADD COLUMN reservation_deposit_rate DECIMAL(5,4) NOT NULL DEFAULT 0.2000;
