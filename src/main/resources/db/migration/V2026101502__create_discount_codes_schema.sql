-- Migration 2: Create discount_codes table and add voucher tracking to reservation_pricing_snapshots
CREATE TABLE discount_codes (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(500) NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    facility_id BINARY(16) NULL,
    policy_id BINARY(16) NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    publication_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    max_uses INT NULL,
    current_uses INT NOT NULL DEFAULT 0,
    created_by BINARY(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_discount_codes_code UNIQUE (code),
    CONSTRAINT fk_discount_codes_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_discount_codes_policy FOREIGN KEY (policy_id) REFERENCES rental_package_policies(id),
    CONSTRAINT fk_discount_codes_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT chk_discount_scope CHECK (scope IN ('GLOBAL', 'FACILITY', 'POLICY')),
    CONSTRAINT chk_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT chk_discount_pub_status CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'INACTIVE'))
) ENGINE=InnoDB;

ALTER TABLE reservation_pricing_snapshots
    ADD COLUMN voucher_code VARCHAR(50) NULL AFTER discount_amount,
    ADD COLUMN voucher_discount_type VARCHAR(20) NULL AFTER voucher_code,
    ADD COLUMN voucher_discount_value DECIMAL(12,2) NULL AFTER voucher_discount_type,
    ADD COLUMN voucher_discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER voucher_discount_value;
