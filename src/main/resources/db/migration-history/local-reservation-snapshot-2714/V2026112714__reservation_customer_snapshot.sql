-- The local return-fee migration already occupies version 2026112708.
SET @customer_name_ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'customer_name'), 'SELECT 1', 'ALTER TABLE reservations ADD COLUMN customer_name VARCHAR(160) NULL');
PREPARE customer_name_statement FROM @customer_name_ddl;
EXECUTE customer_name_statement;
DEALLOCATE PREPARE customer_name_statement;

SET @customer_email_ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'customer_email'), 'SELECT 1', 'ALTER TABLE reservations ADD COLUMN customer_email VARCHAR(320) NULL');
PREPARE customer_email_statement FROM @customer_email_ddl;
EXECUTE customer_email_statement;
DEALLOCATE PREPARE customer_email_statement;

UPDATE reservations r JOIN users u ON u.id = r.customer_id
SET r.customer_name = COALESCE(r.customer_name, u.full_name),
    r.customer_email = COALESCE(r.customer_email, u.email),
    r.customer_phone = COALESCE(r.customer_phone, u.phone),
    r.customer_address = COALESCE(r.customer_address, u.permanent_address);
