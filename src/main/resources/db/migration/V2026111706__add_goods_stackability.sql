ALTER TABLE reservation_goods_items ADD COLUMN stackable BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE reservation_goods_items
SET stackable = CASE
    WHEN fragile = FALSE AND max_stack_layers > 1 THEN TRUE
    ELSE FALSE
END;
