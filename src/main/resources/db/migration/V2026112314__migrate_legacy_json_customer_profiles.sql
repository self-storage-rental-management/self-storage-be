-- Preserve profiles saved by the JSON profile implementation before using embedded columns.
UPDATE users SET
    profile_identity_document_number = COALESCE(profile_identity_document_number, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.identityDocumentNumber')), 'null'), '')),
    profile_emergency_contact_relationship = COALESCE(profile_emergency_contact_relationship, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.emergencyContactRelationship')), 'null'), '')),
    profile_emergency_contact_email = COALESCE(profile_emergency_contact_email, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.emergencyContactEmail')), 'null'), '')),
    profile_secondary_emergency_contact_name = COALESCE(profile_secondary_emergency_contact_name, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.secondaryEmergencyContactName')), 'null'), '')),
    profile_secondary_emergency_contact_phone = COALESCE(profile_secondary_emergency_contact_phone, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.secondaryEmergencyContactPhone')), 'null'), '')),
    profile_secondary_emergency_contact_relationship = COALESCE(profile_secondary_emergency_contact_relationship, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.secondaryEmergencyContactRelationship')), 'null'), '')),
    profile_secondary_emergency_contact_email = COALESCE(profile_secondary_emergency_contact_email, NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.secondaryEmergencyContactEmail')), 'null'), '')),
    profile_emergency_contact_consent = profile_emergency_contact_consent OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.emergencyContactConsent')) = 'true', FALSE),
    profile_secondary_emergency_contact_consent = profile_secondary_emergency_contact_consent OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.secondaryEmergencyContactConsent')) = 'true', FALSE)
WHERE profile_details IS NOT NULL;

UPDATE users
SET profile_identity_verified_at = COALESCE(profile_identity_verified_at,
    STR_TO_DATE(LEFT(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.identityVerifiedAt')), 'T', ' '), 19), '%Y-%m-%d %H:%i:%s'))
WHERE profile_details IS NOT NULL
    AND JSON_UNQUOTE(JSON_EXTRACT(profile_details, '$.identityVerifiedAt')) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}T';

UPDATE users
SET identity_document_hash = LOWER(SHA2(profile_identity_document_number, 256))
WHERE profile_identity_document_number IS NOT NULL AND profile_identity_document_number <> '';
