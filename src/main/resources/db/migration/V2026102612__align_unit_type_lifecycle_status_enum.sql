-- Align the existing MySQL status column with UnitTypeStatus.
-- Preserve active/inactive rows while allowing the Manager -> BO pricing lifecycle.
ALTER TABLE unit_types MODIFY COLUMN status ENUM(
    'draft','awaiting_pricing','active','inactive','archived'
) NOT NULL;
