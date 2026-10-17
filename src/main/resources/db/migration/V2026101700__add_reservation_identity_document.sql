SET @identity_document_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'reservations'
      AND column_name = 'identity_document_number'
);

SET @identity_document_column_ddl = IF(
    @identity_document_column_exists = 0,
    'ALTER TABLE reservations ADD COLUMN identity_document_number VARCHAR(30) NULL AFTER request_fingerprint',
    'SELECT 1'
);

PREPARE identity_document_column_statement FROM @identity_document_column_ddl;
EXECUTE identity_document_column_statement;
DEALLOCATE PREPARE identity_document_column_statement;
