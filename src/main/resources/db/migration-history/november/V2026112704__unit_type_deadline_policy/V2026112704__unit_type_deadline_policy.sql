ALTER TABLE unit_types
 ADD COLUMN setup_deadline_days INT NOT NULL DEFAULT 5,
 ADD COLUMN setup_max_extension_days INT NOT NULL DEFAULT 2,
 ADD COLUMN mismatch_return_days INT NOT NULL DEFAULT 3,
 ADD COLUMN mismatch_return_max_extension_days INT NOT NULL DEFAULT 2;
UPDATE unit_types SET
 setup_deadline_days = CASE UPPER(TRIM(code)) WHEN 'M' THEN 7 WHEN 'L' THEN 9 WHEN 'XL' THEN 12 ELSE 5 END,
 setup_max_extension_days = CASE UPPER(TRIM(code)) WHEN 'M' THEN 3 WHEN 'L' THEN 4 WHEN 'XL' THEN 5 ELSE 2 END,
 mismatch_return_days = CASE UPPER(TRIM(code)) WHEN 'M' THEN 5 WHEN 'L' THEN 7 WHEN 'XL' THEN 10 ELSE 3 END;
