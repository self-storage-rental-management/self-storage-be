ALTER TABLE goods_type_catalog ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE goods_material_catalog ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

SET @goods_order := 0;
UPDATE goods_type_catalog
SET sort_order = (@goods_order := @goods_order + 10)
ORDER BY CASE WHEN code = 'OTHER' THEN 1 ELSE 0 END, display_name, code;

UPDATE goods_type_catalog
SET sort_order = 2147483647
WHERE code = 'OTHER';

UPDATE goods_material_catalog material
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY goods_type_id ORDER BY display_name, code) * 10 AS position
    FROM goods_material_catalog
) ordered ON ordered.id = material.id
SET material.sort_order = ordered.position;

CREATE INDEX idx_goods_type_catalog_sort ON goods_type_catalog(status, sort_order);
CREATE INDEX idx_goods_material_catalog_sort ON goods_material_catalog(goods_type_id, status, sort_order);
