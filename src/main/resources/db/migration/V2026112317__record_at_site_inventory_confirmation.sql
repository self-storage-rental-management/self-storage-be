-- At-site inventory resolution records the arrangement agreed with the verified customer
-- who is physically present at the facility. The confirmation needs an operator, the moment
-- it was recorded, the evidence, and the version of the inspection record it confirms.
--
-- These values live in their own table instead of new check_ins columns on purpose:
-- check_ins already uses 16.278 utf8mb4 characters (~65.112 bytes) against MySQL's
-- 65.535-byte row limit, so adding further columns to it fails with "Row size too large".
CREATE TABLE check_in_inventory_confirmations (
    check_in_id BINARY(16) NOT NULL,
    confirmed_by BINARY(16) NULL,
    confirmed_by_name TEXT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    evidence_json LONGTEXT NULL,
    result_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (check_in_id),
    CONSTRAINT fk_check_in_inventory_confirmations_check_in
        FOREIGN KEY (check_in_id) REFERENCES check_ins (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- No row is created for existing check-ins: a legacy record without a confirmation
-- must keep showing "Chưa ghi nhận" instead of an invented confirmation.
