-- Additive only. Do not infer publication events for existing rentals.
ALTER TABLE rentals
    ADD COLUMN inventory_removal_published_at DATETIME(6) NULL,
    ADD COLUMN inventory_removal_deadline_at DATETIME(6) NULL;
