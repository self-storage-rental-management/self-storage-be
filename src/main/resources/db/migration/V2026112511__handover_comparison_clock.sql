ALTER TABLE reservations ADD COLUMN first_handover_comparison_at DATETIME(6) NULL,
    ADD COLUMN disputed_arrival_at DATETIME(6) NULL,
    ADD COLUMN arrival_review_opened_at DATETIME(6) NULL,
    ADD COLUMN arrival_review_evidence VARCHAR(500) NULL;
CREATE INDEX ix_reservation_handover_comparison ON reservations (status, first_handover_comparison_at);
