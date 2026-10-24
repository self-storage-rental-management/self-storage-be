ALTER TABLE unit_types
    ADD COLUMN published_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN accepted_goods_json LONGTEXT NULL;

UPDATE unit_types
SET accepted_goods_json = JSON_OBJECT(
        'categoryIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_type_catalog WHERE status = 'ACTIVE'), JSON_ARRAY()),
        'materialIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_material_catalog WHERE status = 'ACTIVE'), JSON_ARRAY())
    ),
    published_revision = CASE WHEN status = 'active' THEN 1 ELSE 0 END
WHERE accepted_goods_json IS NULL;

ALTER TABLE unit_types
    MODIFY accepted_goods_json LONGTEXT NOT NULL;

ALTER TABLE unit_type_change_requests
    ADD COLUMN requires_repricing BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reservation_quotes
    ADD COLUMN unit_type_snapshot_json LONGTEXT NULL,
    ADD COLUMN unit_type_revision BIGINT NOT NULL DEFAULT 0;

UPDATE reservation_quotes q
JOIN unit_types u ON u.id = q.unit_type_id
SET q.unit_type_snapshot_json = JSON_OBJECT(
        'code', u.code, 'name', u.name, 'description', u.description,
        'lengthM', u.length_m, 'widthM', u.width_m, 'heightM', u.height_m,
        'maxLoadKg', u.max_load_kg, 'publishedRevision', u.published_revision,
        'acceptedGoods', u.accepted_goods_json
    ),
    q.unit_type_revision = u.published_revision
WHERE q.unit_type_snapshot_json IS NULL;

ALTER TABLE reservation_quotes
    MODIFY unit_type_snapshot_json LONGTEXT NOT NULL;
