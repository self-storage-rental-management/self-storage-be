-- Preserve the facility, unit type, rack, pallet and goods data used by a reservation.
-- Existing rows stay readable but are marked as legacy when the old snapshot data is absent.

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes'
         AND column_name = 'facility_snapshot_json') = 0,
    'ALTER TABLE reservation_quotes ADD COLUMN facility_snapshot_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_quotes'
         AND column_name = 'snapshot_hash') = 0,
    'ALTER TABLE reservation_quotes ADD COLUMN snapshot_hash VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots'
         AND column_name = 'facility_snapshot_json') = 0,
    'ALTER TABLE reservation_pricing_snapshots ADD COLUMN facility_snapshot_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots'
         AND column_name = 'unit_type_snapshot_json') = 0,
    'ALTER TABLE reservation_pricing_snapshots ADD COLUMN unit_type_snapshot_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots'
         AND column_name = 'goods_snapshot_json') = 0,
    'ALTER TABLE reservation_pricing_snapshots ADD COLUMN goods_snapshot_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots'
         AND column_name = 'assigned_unit_snapshot_json') = 0,
    'ALTER TABLE reservation_pricing_snapshots ADD COLUMN assigned_unit_snapshot_json LONGTEXT NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'reservation_pricing_snapshots'
         AND column_name = 'snapshot_hash') = 0,
    'ALTER TABLE reservation_pricing_snapshots ADD COLUMN snapshot_hash VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_sql;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;
