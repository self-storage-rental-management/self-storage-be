ALTER TABLE check_ins
    ADD COLUMN inventory_appointment_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_arrived_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_identity_verified_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_identity_verified_by BINARY(16) NULL,
    ADD COLUMN inventory_identity_photo_id BINARY(16) NULL,
    ADD CONSTRAINT fk_check_in_inventory_verifier FOREIGN KEY (inventory_identity_verified_by) REFERENCES users(id),
    ADD CONSTRAINT fk_check_in_inventory_photo FOREIGN KEY (inventory_identity_photo_id) REFERENCES file_assets(id);
