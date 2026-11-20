ALTER TABLE reservations ADD COLUMN pallet_held_unit_id BINARY(16) NULL,
    ADD CONSTRAINT fk_reservation_pallet_held_unit FOREIGN KEY (pallet_held_unit_id) REFERENCES storage_units(id),
    ADD INDEX ix_reservation_pallet_hold (pallet_held_unit_id, assigned_unit_id, status);

ALTER TABLE reservation_goods_items ADD COLUMN max_stack_layers INT NOT NULL DEFAULT 1;
