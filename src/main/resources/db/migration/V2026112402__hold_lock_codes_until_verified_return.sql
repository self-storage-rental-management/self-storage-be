-- Fail on existing duplicate active allocations so they can be reconciled.
ALTER TABLE access_credentials
    ADD COLUMN lock_code_held BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN held_lock_reference VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN credential_type = 'LOCK' AND (lock_code_held OR status = 'ACTIVE')
            THEN LOWER(TRIM(reference)) END
    ) VIRTUAL,
    ADD CONSTRAINT uq_access_credential_held_lock UNIQUE (facility_id, held_lock_reference);

-- Keep every active lock and, when none is active, hold the latest historical
-- allocation. Old inspection flags cannot certify that all keys were recovered.
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
WHERE backfill.status = 'ACTIVE' OR backfill.allocation_rank = 1;

DROP TEMPORARY TABLE lock_code_backfill;

ALTER TABLE return_cases
    ADD COLUMN lock_inspection_result VARCHAR(32) NULL;
