ALTER TABLE users
    ADD COLUMN profile_identity_document_number VARCHAR(30) NULL,
    ADD COLUMN profile_emergency_contact_relationship VARCHAR(80) NULL,
    ADD COLUMN profile_emergency_contact_email VARCHAR(320) NULL,
    ADD COLUMN profile_emergency_contact_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN profile_secondary_emergency_contact_name VARCHAR(160) NULL,
    ADD COLUMN profile_secondary_emergency_contact_phone VARCHAR(30) NULL,
    ADD COLUMN profile_secondary_emergency_contact_relationship VARCHAR(80) NULL,
    ADD COLUMN profile_secondary_emergency_contact_email VARCHAR(320) NULL,
    ADD COLUMN profile_secondary_emergency_contact_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE check_ins ADD COLUMN emergency_contacts_snapshot LONGTEXT NULL;
ALTER TABLE contracts ADD COLUMN emergency_contacts_snapshot LONGTEXT NULL;
