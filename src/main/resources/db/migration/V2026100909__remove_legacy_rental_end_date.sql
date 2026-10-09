SET @storagehub_rentals_end_date = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rentals'
      AND column_name = 'end_date'
);
SET @storagehub_drop_rentals_end_date = IF(
    @storagehub_rentals_end_date = 1,
    'ALTER TABLE rentals DROP COLUMN end_date',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_drop_rentals_end_date;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;
