-- Preserve existing outcomes and allow an explicit staff-at-site pending outcome.
ALTER TABLE check_ins MODIFY COLUMN inventory_resolution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED';
