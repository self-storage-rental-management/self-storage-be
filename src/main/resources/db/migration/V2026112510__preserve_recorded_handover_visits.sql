-- Staff recorded these older visits before arrival tracking was available.
SET @handover_arrived_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'reservations' AND column_name = 'handover_arrived_at') = 0,
    'ALTER TABLE reservations ADD COLUMN handover_arrived_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE handover_arrived_stmt FROM @handover_arrived_ddl;
EXECUTE handover_arrived_stmt;
DEALLOCATE PREPARE handover_arrived_stmt;

SET @old_id_cols = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'reservations'
      AND column_name IN ('identity_documents_received_at', 'identity_supplement_requested_at', 'identity_hold_reason')
);
SET @backfill_visits = IF(
    @old_id_cols = 3,
    'UPDATE reservations r SET handover_arrived_at = COALESCE(r.identity_documents_received_at, r.identity_supplement_requested_at, (SELECT MIN(a.created_at) FROM check_ins c JOIN activity_logs a ON a.entity_id = c.id WHERE c.reservation_id = r.id AND a.entity_type = ''CheckIn'' AND a.action = ''CHECKIN_IDENTITY_HOLD''), r.updated_at) WHERE r.handover_arrived_at IS NULL AND (r.identity_documents_received_at IS NOT NULL OR r.identity_supplement_requested_at IS NOT NULL OR r.identity_hold_reason IS NOT NULL)',
    'SELECT 1'
);
PREPARE backfill_visits_stmt FROM @backfill_visits;
EXECUTE backfill_visits_stmt;
DEALLOCATE PREPARE backfill_visits_stmt;