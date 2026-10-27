ALTER TABLE reservation_goods_items
 ADD COLUMN catalog_category_id BINARY(16) NULL,
 ADD COLUMN catalog_material_id BINARY(16) NULL,
 ADD COLUMN category_code VARCHAR(40) NULL,
 ADD COLUMN category_name VARCHAR(160) NULL,
 ADD COLUMN material_code VARCHAR(40) NULL;
