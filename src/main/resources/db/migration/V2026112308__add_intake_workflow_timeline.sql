-- No retrospective deadlines or fabricated attendance for existing records.
ALTER TABLE rentals
 ADD COLUMN setup_completion_deadline_at DATETIME(6) NULL,
 ADD COLUMN setup_extension_days INT NULL,
 ADD COLUMN setup_extension_count INT NULL,
 ADD COLUMN inventory_appointment_at DATETIME(6) NULL,
 ADD COLUMN inventory_arrived_at DATETIME(6) NULL,
 ADD COLUMN inventory_identity_verified_at DATETIME(6) NULL,
 ADD COLUMN inventory_identity_photo_id BINARY(16) NULL,
 ADD COLUMN inventory_identity_verified_by BINARY(16) NULL,
 ADD COLUMN inventory_result_published_at DATETIME(6) NULL,
 ADD COLUMN inventory_remainder_deadline_at DATETIME(6) NULL,
 ADD COLUMN workflow_history_json LONGTEXT NULL;

ALTER TABLE return_cases ADD COLUMN ready_for_inspection_at DATETIME(6) NULL;
