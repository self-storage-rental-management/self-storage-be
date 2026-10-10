-- Managers coordinate Support tickets and need both queue-read and assignment permissions.
-- Keep this additive so any other custom permissions on MANAGER remain unchanged.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT role_row.id, permission_row.id
FROM roles role_row
JOIN permissions permission_row
  ON permission_row.code IN ('support:read', 'support:update')
WHERE role_row.code = 'MANAGER';
