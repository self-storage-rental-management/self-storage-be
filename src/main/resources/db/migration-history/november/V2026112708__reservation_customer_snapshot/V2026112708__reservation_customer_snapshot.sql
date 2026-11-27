ALTER TABLE reservations
    ADD COLUMN customer_name VARCHAR(160) NULL,
    ADD COLUMN customer_email VARCHAR(320) NULL;

-- Historic names/emails were not stored on bookings. Freeze the currently
-- available values once; exact pre-edit values cannot be reconstructed here.
UPDATE reservations r
JOIN users u ON u.id = r.customer_id
SET r.customer_name = u.full_name,
    r.customer_email = u.email,
    r.customer_phone = COALESCE(r.customer_phone, u.phone),
    r.customer_address = COALESCE(r.customer_address, u.permanent_address);
