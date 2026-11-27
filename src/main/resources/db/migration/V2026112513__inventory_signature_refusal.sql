ALTER TABLE check_in_inventory_confirmations
    ADD COLUMN inventory_refusal_report TEXT NULL,
    ADD COLUMN inventory_refusal_submitted_at DATETIME(6) NULL,
    ADD COLUMN inventory_refusal_approved BOOLEAN NULL,
    ADD INDEX idx_check_in_inventory_refusal (inventory_refusal_approved, inventory_refusal_submitted_at);
