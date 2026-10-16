-- Preserve the exact previous states for recovery; only known demo seed units qualify.
CREATE TABLE IF NOT EXISTS demo_occupancy_repair_2026101401 (
    unit_id BINARY(16) PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    previous_updated_at TIMESTAMP(6) NULL,
    repaired_at TIMESTAMP(6) NOT NULL
);

INSERT IGNORE INTO demo_occupancy_repair_2026101401
SELECT s.id AS unit_id, s.code, s.status AS previous_status, s.updated_at AS previous_updated_at,
       CURRENT_TIMESTAMP(6) AS repaired_at
FROM storage_units s
WHERE s.status = 'occupied'
  AND s.code IN ('HCM-Q1-F01-S-001', 'HCM-Q1-F01-M-001', 'HCM-Q1-F01-L-001',
                 'BD-F01-S-001', 'BD-F01-M-001', 'BD-F01-L-001', 'BD-F01-XL-001', 'BD-F01-XL-002')
  AND NOT EXISTS (SELECT 1 FROM rentals r WHERE r.storage_unit_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM unit_assignments a WHERE a.storage_unit_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.assigned_unit_id = s.id);

UPDATE storage_units s
JOIN demo_occupancy_repair_2026101401 b ON b.unit_id = s.id
SET s.status = 'available', s.updated_at = CURRENT_TIMESTAMP(6)
WHERE s.status = 'occupied'
  AND NOT EXISTS (SELECT 1 FROM rentals r WHERE r.storage_unit_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM unit_assignments a WHERE a.storage_unit_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.assigned_unit_id = s.id);
