ALTER TABLE return_cases
    ADD COLUMN missing_pallet_count INT NULL,
    ADD COLUMN pallet_loss_fee DECIMAL(14,2) NULL;
