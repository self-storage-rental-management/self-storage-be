-- Keep fresh Flyway-managed databases aligned with the Reservation entity while
-- remaining safe for local databases where Hibernate already created the column.
SET @appointment_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'reservations'
      AND column_name = 'appointment_at'
);

SET @appointment_column_ddl = IF(
    @appointment_column_exists = 0,
    'ALTER TABLE reservations ADD COLUMN appointment_at TIMESTAMP(6) NULL AFTER end_date',
    'SELECT 1'
);

PREPARE appointment_column_statement FROM @appointment_column_ddl;
EXECUTE appointment_column_statement;
DEALLOCATE PREPARE appointment_column_statement;
