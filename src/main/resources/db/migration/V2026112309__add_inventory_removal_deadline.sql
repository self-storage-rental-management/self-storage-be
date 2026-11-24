ALTER TABLE rentals
    ADD COLUMN inventory_removal_notified_at TIMESTAMP(6) NULL,
    ADD COLUMN inventory_removal_deadline_at TIMESTAMP(6) NULL;
