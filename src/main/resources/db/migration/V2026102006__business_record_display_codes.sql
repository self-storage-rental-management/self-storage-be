CREATE TABLE business_record_code_sequences (
    type_code CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    serial_value BIGINT NOT NULL,
    PRIMARY KEY (type_code,period),
    CONSTRAINT ck_record_code_sequence CHECK (serial_value BETWEEN 1 AND 1000000)
) ENGINE=InnoDB;

CREATE TABLE business_record_codes (
    resource_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    type_code CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_business_record_display_code (display_code)
) ENGINE=InnoDB;

-- Dates in the database are UTC. Historical entity dates are kept unchanged.
INSERT INTO business_record_codes(resource_key,type_code,period,display_code,recorded_at)
SELECT resource_key,type_code,period,
    CONCAT(type_code,'-',period,'-',LPAD(ROW_NUMBER() OVER (PARTITION BY type_code,period ORDER BY recorded_at,resource_key),6,'0')),
    recorded_at
FROM (
    SELECT resource_key,type_code,DATE_FORMAT(MIN(recorded_at) + INTERVAL 7 HOUR,'%y%m') period,MIN(recorded_at) recorded_at
    FROM (
        SELECT CONCAT('GH:',LOWER(BIN_TO_UUID(id))) resource_key,'GH' type_code,created_at recorded_at FROM renewals
        UNION ALL
        SELECT CONCAT('HT:',LOWER(BIN_TO_UUID(id))),'HT',created_at FROM support_tickets
        UNION ALL
        SELECT case_ref,'QH',MIN(recorded_at) FROM overdue_follow_ups GROUP BY case_ref
        UNION ALL
        SELECT CONCAT('RENTAL_TERM:',LOWER(BIN_TO_UUID(id))),'QH',UTC_TIMESTAMP(6)
        FROM rentals WHERE status='active' AND actual_returned_at IS NULL AND completed_at IS NULL
            AND start_date<=contract_end_date AND contract_end_date<DATE(UTC_TIMESTAMP()+INTERVAL 7 HOUR)
    ) candidates0
    GROUP BY resource_key,type_code
) candidates;

INSERT INTO business_record_code_sequences(type_code,period,serial_value)
SELECT type_code,period,MAX(CAST(RIGHT(display_code,6) AS UNSIGNED))
FROM business_record_codes GROUP BY type_code,period;
