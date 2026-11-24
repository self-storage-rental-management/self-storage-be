ALTER TABLE check_ins
 ADD COLUMN inventory_draft_json LONGTEXT NULL,
 ADD COLUMN inventory_draft_saved_at TIMESTAMP(6) NULL,
 ADD COLUMN inventory_draft_revision BIGINT NOT NULL DEFAULT 0;
