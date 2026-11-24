-- 1. Add lock_code_held column first without unique constraint.
ALTER TABLE access_credentials
    ADD COLUMN lock_code_held BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Reconcile any pre-existing duplicate active allocations in development/historical data.
-- Keep only the newest active allocation active, revoking older duplicate active allocations.
CREATE TEMPORARY TABLE duplicate_active_locks AS
SELECT id,
    ROW_NUMBER() OVER (
        PARTITION BY facility_id, LOWER(TRIM(reference))
        ORDER BY issued_at DESC, id DESC
    ) AS active_rank
FROM access_credentials
WHERE credential_type = 'LOCK' AND status = 'ACTIVE';

UPDATE access_credentials credential
JOIN duplicate_active_locks duplicates ON duplicates.id = credential.id
SET credential.status = 'REVOKED'
WHERE duplicates.active_rank > 1;

DROP TEMPORARY TABLE duplicate_active_locks;

-- 3. Keep the active lock and, when none is active, hold the latest historical allocation.
CREATE TEMPORARY TABLE lock_code_backfill AS
SELECT id, status,
    ROW_NUMBER() OVER (
        PARTITION BY facility_id, LOWER(TRIM(reference))
        ORDER BY (status = 'ACTIVE') DESC, issued_at DESC, id DESC
    ) AS allocation_rank
FROM access_credentials
WHERE credential_type = 'LOCK';

UPDATE access_credentials credential
JOIN lock_code_backfill backfill ON backfill.id = credential.id
SET credential.lock_code_held = TRUE
WHERE backfill.allocation_rank = 1;

DROP TEMPORARY TABLE lock_code_backfill;

-- 4. Now add the virtual held_lock_reference column and unique constraint safely.
ALTER TABLE access_credentials
    ADD COLUMN held_lock_reference VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN credential_type = 'LOCK' AND (lock_code_held OR status = 'ACTIVE')
            THEN LOWER(TRIM(reference)) END
    ) VIRTUAL,
    ADD CONSTRAINT uq_access_credential_held_lock UNIQUE (facility_id, held_lock_reference);

-- 5. Add return inspection result column to return_cases.
ALTER TABLE return_cases
    ADD COLUMN lock_inspection_result VARCHAR(32) NULL;
