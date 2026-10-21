ALTER TABLE reservations
    ADD COLUMN customer_phone VARCHAR(40) NULL AFTER identity_document_number,
    ADD COLUMN customer_address VARCHAR(500) NULL AFTER customer_phone;
