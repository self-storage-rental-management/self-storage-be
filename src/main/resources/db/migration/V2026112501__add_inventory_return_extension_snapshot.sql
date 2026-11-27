ALTER TABLE rentals
    ADD COLUMN inventory_return_extension_days INT NULL,
    ADD COLUMN inventory_return_extension_count INT NOT NULL DEFAULT 0;

ALTER TABLE return_cases
    ADD COLUMN return_extension_days INT NULL,
    ADD COLUMN return_extension_count INT NOT NULL DEFAULT 0;
