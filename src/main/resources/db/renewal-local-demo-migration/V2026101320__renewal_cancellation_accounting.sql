-- Unwind unearned Renewal balances without overwriting accepted terms or received funds.
CREATE TABLE renewal_ledger_cancellation_credits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obligation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    source_ref VARCHAR(200) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_renewal_cancellation_source (rental_id,source_ref),
    CONSTRAINT fk_renewal_cancellation_obligation FOREIGN KEY (obligation_id,rental_id) REFERENCES rental_ledger_obligations(id,rental_id),
    CONSTRAINT ck_renewal_cancellation_positive CHECK (amount>0)
) ENGINE=InnoDB;
