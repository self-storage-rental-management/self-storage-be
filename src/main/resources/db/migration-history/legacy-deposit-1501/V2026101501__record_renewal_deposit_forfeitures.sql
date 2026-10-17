-- Immutable disposition of an already allocated Renewal deposit. Never a second charge.
CREATE TABLE renewal_deposit_forfeitures (
    renewal_id VARCHAR(36) NOT NULL PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL UNIQUE,
    receipt_id VARCHAR(36) NOT NULL UNIQUE,
    obligation_id VARCHAR(36) NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    forfeited_at TIMESTAMP(6) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    CHECK (amount > 0)
) ENGINE=InnoDB;
