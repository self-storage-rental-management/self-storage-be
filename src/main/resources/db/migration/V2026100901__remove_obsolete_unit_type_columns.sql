SET @storagehub_unit_types_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'unit_types'
);

SET @storagehub_drop_area_m2 = IF(
    @storagehub_unit_types_exists = 1 AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'unit_types'
          AND column_name = 'area_m2'
    ),
    'ALTER TABLE unit_types DROP COLUMN area_m2',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_drop_area_m2;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_drop_volume_m3 = IF(
    @storagehub_unit_types_exists = 1 AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'unit_types'
          AND column_name = 'volume_m3'
    ),
    'ALTER TABLE unit_types DROP COLUMN volume_m3',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_drop_volume_m3;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;

SET @storagehub_drop_price_per_m3 = IF(
    @storagehub_unit_types_exists = 1 AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'unit_types'
          AND column_name = 'price_per_m3'
    ),
    'ALTER TABLE unit_types DROP COLUMN price_per_m3',
    'SELECT 1'
);
PREPARE storagehub_statement FROM @storagehub_drop_price_per_m3;
EXECUTE storagehub_statement;
DEALLOCATE PREPARE storagehub_statement;
