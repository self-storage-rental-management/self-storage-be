-- Migration 1: Extend rental_package_policies with publication_status, description, rental_years
ALTER TABLE rental_package_policies
    ADD COLUMN description VARCHAR(500) NULL AFTER name,
    ADD COLUMN rental_years INT NULL AFTER rental_months,
    ADD COLUMN publication_status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE' AFTER policy_version;

-- Calculate rental_years for existing records that are divisible by 12
UPDATE rental_package_policies
SET rental_years = rental_months / 12
WHERE rental_months >= 12 AND rental_months % 12 = 0;

-- Safety rule: All old policies default to INACTIVE so BO can explicitly review and publish them.
UPDATE rental_package_policies
SET publication_status = 'INACTIVE';
