-- The merged intake timeline already adds the deadline in V2026112307.
-- Keep this legacy notification column without adding the same deadline twice.
ALTER TABLE rentals
    ADD COLUMN inventory_removal_notified_at TIMESTAMP(6) NULL;
