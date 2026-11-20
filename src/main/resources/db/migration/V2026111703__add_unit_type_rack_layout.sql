ALTER TABLE unit_types ADD COLUMN rack_layout_json LONGTEXT NULL;

-- Promote an existing layout only if all configured, non-archived units agree.
-- Keep per-unit snapshots for existing reservations on types with conflicting layouts.
UPDATE unit_types t
JOIN (
    SELECT unit_type_id, MIN(id) AS source_unit_id
    FROM storage_units
    WHERE rack_layout_json IS NOT NULL AND status <> 'archived'
    GROUP BY unit_type_id
    HAVING COUNT(DISTINCT SHA2(JSON_EXTRACT(rack_layout_json, '$.result.configuration'), 256)) = 1
) layouts ON layouts.unit_type_id = t.id
JOIN storage_units u ON u.id = layouts.source_unit_id
SET t.rack_layout_json = u.rack_layout_json,
    t.rack_count = JSON_LENGTH(JSON_EXTRACT(u.rack_layout_json, '$.result.configuration.racks')),
    t.rack_length_m = GREATEST(0.01, ROUND(JSON_EXTRACT(u.rack_layout_json, '$.result.configuration.racks[0].lengthCm') / 100, 2)),
    t.rack_width_m = GREATEST(0.01, ROUND(JSON_EXTRACT(u.rack_layout_json, '$.result.configuration.racks[0].widthCm') / 100, 2)),
    t.rack_height_m = GREATEST(0.01, ROUND(JSON_EXTRACT(u.rack_layout_json, '$.result.configuration.racks[0].heightCm') / 100, 2)),
    t.small_box_capacity = JSON_EXTRACT(u.rack_layout_json, '$.result.smallBoxesPerUnit'),
    t.large_box_capacity = JSON_EXTRACT(u.rack_layout_json, '$.result.largeBoxesPerUnit');
