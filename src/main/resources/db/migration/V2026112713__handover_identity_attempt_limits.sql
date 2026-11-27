-- Keep identity state on the existing secondary table: check_ins is near MySQL's row-size limit.
ALTER TABLE check_in_identity_display
    ADD COLUMN identity_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN identity_correction_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN approved_identity_correction VARCHAR(12) NULL;

-- Legacy check-ins without a secondary row must also have initialized counters.
INSERT INTO check_in_identity_display (id, identity_failed_attempts, identity_correction_failed_attempts)
SELECT c.id, 0, 0 FROM check_ins c
LEFT JOIN check_in_identity_display d ON d.id = c.id
WHERE d.id IS NULL;
