ALTER TABLE payments
    MODIFY COLUMN reservation_id BINARY(16) NULL,
    ADD COLUMN renewal_id BINARY(16) NULL AFTER reservation_id,
    ADD COLUMN renewal_revision_id BINARY(16) NULL AFTER renewal_id,
    ADD COLUMN payment_deadline TIMESTAMP(6) NULL AFTER renewal_revision_id,
    ADD CONSTRAINT fk_payments_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id),
    ADD CONSTRAINT fk_payments_renewal_revision FOREIGN KEY (renewal_revision_id) REFERENCES renewal_accepted_revisions(id),
    ADD CONSTRAINT ck_payments_single_resource CHECK (
        (reservation_id IS NOT NULL AND renewal_id IS NULL AND renewal_revision_id IS NULL)
        OR (reservation_id IS NULL AND renewal_id IS NOT NULL AND renewal_revision_id IS NOT NULL)
    );

CREATE INDEX idx_payments_renewal_revision
    ON payments (renewal_id, renewal_revision_id, purpose, created_at);

ALTER TABLE renewal_operation_states
    ADD COLUMN full_rent_payment_ref BINARY(16) NULL AFTER deposit_paid_at,
    ADD COLUMN full_rent_paid_at TIMESTAMP(6) NULL AFTER full_rent_payment_ref;
