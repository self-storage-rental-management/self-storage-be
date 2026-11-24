ALTER TABLE rentals
    ADD COLUMN inventory_return_window_days INT NULL,
    ADD COLUMN inventory_return_policy_version VARCHAR(64) NULL;

ALTER TABLE return_cases
    ADD COLUMN return_window_days INT NULL,
    ADD COLUMN return_policy_version VARCHAR(64) NULL;
