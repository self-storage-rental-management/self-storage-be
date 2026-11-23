-- Assign every historical rental a public SH-###### code in one migration. This is
-- intentionally independent of the coordinator's bounded background batches.
CREATE TEMPORARY TABLE rental_business_code_backfill (
    resource_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    period CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    slot BIGINT NOT NULL UNIQUE
) ENGINE=InnoDB;

INSERT INTO rental_business_code_backfill(resource_key, period, recorded_at, slot)
SELECT CONCAT('TH:', LOWER(BIN_TO_UUID(rental.id))),
       DATE_FORMAT(rental.created_at + INTERVAL 7 HOUR, '%y%m'),
       rental.created_at,
       ROW_NUMBER() OVER (ORDER BY rental.created_at, rental.id)
FROM rentals rental
LEFT JOIN business_record_codes existing
    ON existing.resource_key = CONCAT('TH:', LOWER(BIN_TO_UUID(rental.id)))
WHERE existing.resource_key IS NULL;

SET @rental_code_backfill_needed = (SELECT COUNT(*) FROM rental_business_code_backfill);
SET @rental_code_backfill_occupied = (
    SELECT COUNT(*) FROM business_record_codes
    WHERE display_code REGEXP '^SH-[1-9][0-9]{5}$'
);
SET @rental_code_backfill_scan = LEAST(
    900000,
    @rental_code_backfill_needed + @rental_code_backfill_occupied
);

INSERT INTO business_record_codes(resource_key, type_code, period, display_code, recorded_at)
SELECT missing.resource_key,
       'TH',
       missing.period,
       CONCAT('SH-', LPAD(available.candidate, 6, '0')),
       missing.recorded_at
FROM rental_business_code_backfill missing
JOIN (
    SELECT candidates.candidate,
           ROW_NUMBER() OVER (ORDER BY candidates.candidate) AS slot
    FROM (
        SELECT hundred_thousands.d * 100000
             + ten_thousands.d * 10000
             + thousands.d * 1000
             + hundreds.d * 100
             + tens.d * 10
             + ones.d AS candidate
        FROM (SELECT 1 d UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundred_thousands
        CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
        CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
        CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
        CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
        CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    ) candidates
    LEFT JOIN business_record_codes occupied
        ON occupied.display_code = CONCAT('SH-', LPAD(candidates.candidate, 6, '0'))
    WHERE candidates.candidate < 100000 + @rental_code_backfill_scan
      AND occupied.resource_key IS NULL
) available ON available.slot = missing.slot;

DROP TEMPORARY TABLE rental_business_code_backfill;
