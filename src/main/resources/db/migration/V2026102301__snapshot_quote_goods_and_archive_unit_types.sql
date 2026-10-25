ALTER TABLE reservation_quotes
    ADD COLUMN goods_snapshot_json LONGTEXT NULL;

UPDATE reservation_quotes
SET goods_snapshot_json = '[]'
WHERE goods_snapshot_json IS NULL;

ALTER TABLE reservation_quotes
    MODIFY goods_snapshot_json LONGTEXT NOT NULL;

ALTER TABLE reservation_goods_items
    ADD COLUMN goods_code VARCHAR(24) NULL;

UPDATE reservation_goods_items
SET goods_code = CONCAT('GDS-', SUBSTRING(UPPER(HEX(id)), 1, 12))
WHERE goods_code IS NULL;

ALTER TABLE reservation_goods_items
    MODIFY goods_code VARCHAR(24) NOT NULL,
    ADD CONSTRAINT uk_reservation_goods_item_code UNIQUE (goods_code);
