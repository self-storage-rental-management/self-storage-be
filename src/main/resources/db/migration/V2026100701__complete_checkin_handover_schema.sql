SET @storagehub_checkins_scheduled_at = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'scheduled_at'
);
SET @storagehub_add_scheduled_at = IF(
    @storagehub_checkins_scheduled_at = 0,
    'ALTER TABLE check_ins ADD COLUMN scheduled_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_scheduled_at;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_checkins_readiness_note = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'readiness_note'
);
SET @storagehub_add_readiness_note = IF(
    @storagehub_checkins_readiness_note = 0,
    'ALTER TABLE check_ins ADD COLUMN readiness_note VARCHAR(1000) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_readiness_note;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_checkins_rejection_reason = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'rejection_reason'
);
SET @storagehub_add_rejection_reason = IF(
    @storagehub_checkins_rejection_reason = 0,
    'ALTER TABLE check_ins ADD COLUMN rejection_reason VARCHAR(1000) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_rejection_reason;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_checkins_rejection_disposition = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'rejection_disposition'
);
SET @storagehub_add_rejection_disposition = IF(
    @storagehub_checkins_rejection_disposition = 0,
    'ALTER TABLE check_ins ADD COLUMN rejection_disposition VARCHAR(20) NULL',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_rejection_disposition;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_checkins_version = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND column_name = 'version'
);
SET @storagehub_add_version = IF(
    @storagehub_checkins_version = 0,
    'ALTER TABLE check_ins ADD COLUMN version BIGINT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_version;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

ALTER TABLE check_ins
    MODIFY COLUMN checklist_json VARCHAR(8000) NULL;

SET @storagehub_checkin_constraint_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'check_ins'
      AND constraint_name = 'uk_check_in_reservation'
);
SET @storagehub_add_checkin_constraint = IF(
    @storagehub_checkin_constraint_exists = 0,
    'ALTER TABLE check_ins ADD CONSTRAINT uk_check_in_reservation UNIQUE (reservation_id)',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_add_checkin_constraint;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;
