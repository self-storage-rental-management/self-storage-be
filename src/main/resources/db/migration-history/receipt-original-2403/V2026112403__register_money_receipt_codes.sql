CREATE TABLE money_receipt_codes (
    source_type VARCHAR(16) NOT NULL,
    source_id BINARY(16) NOT NULL,
    receipt_code CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (source_type, source_id),
    UNIQUE KEY uk_money_receipt_code (receipt_code)
) ENGINE=InnoDB;

ALTER TABLE payments ADD COLUMN receipt_code VARCHAR(17) NULL;
