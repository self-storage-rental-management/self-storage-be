CREATE TABLE renewal_installment_payment_quotes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    renewal_id VARCHAR(36) NOT NULL,
    accepted_revision_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    ledger_revision BIGINT NOT NULL,
    amount DECIMAL(20,0) NOT NULL,
    allocations_json LONGTEXT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    INDEX idx_installment_quote_renewal (renewal_id)
) ENGINE=InnoDB;
CREATE TABLE renewal_installment_payment_bindings (
    payment_id VARCHAR(36) NOT NULL PRIMARY KEY,
    quote_id VARCHAR(36) NOT NULL UNIQUE,
    renewal_id VARCHAR(36) NOT NULL,
    INDEX idx_installment_binding_renewal (renewal_id)
) ENGINE=InnoDB;
CREATE TABLE renewal_installment_notices (
    renewal_id VARCHAR(36) NOT NULL,
    installment_id VARCHAR(36) NOT NULL,
    notice_kind VARCHAR(24) NOT NULL,
    PRIMARY KEY (renewal_id, installment_id, notice_kind)
) ENGINE=InnoDB;
