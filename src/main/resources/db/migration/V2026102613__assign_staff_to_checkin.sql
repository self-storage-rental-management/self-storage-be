ALTER TABLE check_ins
    ADD COLUMN assigned_staff_id BINARY(16) NULL;

UPDATE check_ins
SET assigned_staff_id = performed_by
WHERE assigned_staff_id IS NULL;

ALTER TABLE check_ins
    ADD INDEX ix_check_ins_assigned_staff (assigned_staff_id),
    ADD CONSTRAINT fk_check_ins_assigned_staff
        FOREIGN KEY (assigned_staff_id) REFERENCES users(id);
