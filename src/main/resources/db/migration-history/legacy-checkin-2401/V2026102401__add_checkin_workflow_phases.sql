ALTER TABLE check_ins
    ADD COLUMN identity_verified_at TIMESTAMP(6) NULL,
    ADD COLUMN verified_identity_document VARCHAR(30) NULL,
    ADD COLUMN identity_verification_note VARCHAR(1000) NULL,
    ADD COLUMN contract_signed_at TIMESTAMP(6) NULL,
    ADD COLUMN contract_evidence_reference VARCHAR(36) NULL,
    ADD COLUMN payment_collected_at TIMESTAMP(6) NULL,
    ADD COLUMN setup_started_at TIMESTAMP(6) NULL,
    ADD COLUMN setup_due_at TIMESTAMP(6) NULL,
    ADD COLUMN setup_completed_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_verified_at TIMESTAMP(6) NULL,
    ADD COLUMN lock_reference VARCHAR(160) NULL;
