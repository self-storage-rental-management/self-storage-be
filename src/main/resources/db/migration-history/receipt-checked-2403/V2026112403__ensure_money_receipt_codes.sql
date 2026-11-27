-- Validate existing objects before completing a partially installed schema.
DROP PROCEDURE IF EXISTS storagehub_migrate_receipts_2026112403;
DELIMITER //
CREATE PROCEDURE storagehub_migrate_receipts_2026112403()
BEGIN
    DECLARE has_registry BOOLEAN;
    DECLARE has_column BOOLEAN;
    SELECT EXISTS(SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'money_receipt_codes') INTO has_registry;
    SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'payments' AND column_name = 'receipt_code') INTO has_column;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'payments' AND table_type = 'BASE TABLE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Expected payments table';
    END IF;
    IF has_column AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'payments' AND column_name = 'receipt_code' AND column_type = 'varchar(17)'
        AND is_nullable = 'YES' AND column_default IS NULL AND extra = '') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Incompatible payments.receipt_code';
    END IF;
    IF has_registry THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
            AND table_name = 'money_receipt_codes' AND engine = 'InnoDB')
        OR NOT (SELECT COUNT(*) = 3 AND COUNT(CASE WHEN is_nullable = 'NO' AND extra = '' AND (
                (column_name = 'source_type' AND column_type = 'varchar(16)') OR
                (column_name = 'source_id' AND column_type = 'binary(16)') OR
                (column_name = 'receipt_code' AND column_type = 'char(17)' AND collation_name = 'ascii_bin')
            ) THEN 1 END) = 3 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'money_receipt_codes')
        OR NOT (SELECT COUNT(*) = 3 AND COUNT(CASE WHEN sub_part IS NULL AND (
                (index_name = 'PRIMARY' AND seq_in_index = 1 AND column_name = 'source_type') OR
                (index_name = 'PRIMARY' AND seq_in_index = 2 AND column_name = 'source_id') OR
                (index_name = 'uk_money_receipt_code' AND seq_in_index = 1 AND column_name = 'receipt_code')
            ) THEN 1 END) = 3 FROM information_schema.statistics WHERE table_schema = DATABASE()
            AND table_name = 'money_receipt_codes' AND non_unique = 0) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Incompatible money_receipt_codes schema or unique keys';
        END IF;
    ELSE
        CREATE TABLE money_receipt_codes (
            source_type VARCHAR(16) NOT NULL,
            source_id BINARY(16) NOT NULL,
            receipt_code CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY (source_type, source_id),
            UNIQUE KEY uk_money_receipt_code (receipt_code)
        ) ENGINE=InnoDB;
    END IF;
    IF NOT has_column THEN
        ALTER TABLE payments ADD COLUMN receipt_code VARCHAR(17) NULL;
    END IF;
END//
DELIMITER ;
CALL storagehub_migrate_receipts_2026112403();
DROP PROCEDURE storagehub_migrate_receipts_2026112403;
