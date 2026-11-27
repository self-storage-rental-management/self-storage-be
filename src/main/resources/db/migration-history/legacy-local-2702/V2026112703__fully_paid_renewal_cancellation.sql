-- A voluntary cancellation retains every paid renewal receipt without creating a refund.
CREATE TABLE IF NOT EXISTS renewal_payment_forfeitures (
    renewal_id VARCHAR(36) NOT NULL,
    rental_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL UNIQUE,
    receipt_id VARCHAR(36) NOT NULL PRIMARY KEY,
    amount DECIMAL(20,0) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    forfeited_at TIMESTAMP(6) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    CHECK (amount > 0)
) ENGINE=InnoDB;
