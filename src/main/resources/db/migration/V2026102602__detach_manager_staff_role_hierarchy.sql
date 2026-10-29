-- Manager and Staff are separate operational roles. Manager supervision must
-- not inherit Staff execution permissions through the legacy role hierarchy.
UPDATE roles
SET parent_role_id = NULL
WHERE code IN ('MANAGER', 'STAFF', 'BUSINESS');
