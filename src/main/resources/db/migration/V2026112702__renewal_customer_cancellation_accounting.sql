-- Preserve paid receipts and record cancellation separately from refunds or new charges.
CREATE TABLE IF NOT EXISTS renewal_deposit_forfeitures (
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

CREATE TABLE IF NOT EXISTS renewal_ledger_cancellation_credits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obligation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    source_ref VARCHAR(200) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_renewal_cancellation_source (rental_id, source_ref),
    CONSTRAINT ck_renewal_cancellation_positive CHECK (amount > 0)
) ENGINE=InnoDB;
