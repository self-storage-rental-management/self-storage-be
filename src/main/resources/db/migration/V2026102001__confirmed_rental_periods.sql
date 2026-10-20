CREATE TABLE confirmed_rental_periods (
    sequence_no BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rental_id VARCHAR(36) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    start_date DATE NOT NULL,
    stored_end_date DATE NOT NULL,
    end_convention VARCHAR(12) NOT NULL,
    source_ref VARCHAR(200) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_confirmed_period_source (rental_id, source_ref),
    INDEX idx_confirmed_period_current (rental_id, sequence_no)
) ENGINE=InnoDB;
