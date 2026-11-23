ALTER TABLE users
    ADD COLUMN profile_identity_verified_at DATETIME(6) NULL,
    ADD COLUMN identity_document_hash VARCHAR(64) NULL;
UPDATE users SET profile_identity_document_number = REGEXP_REPLACE(profile_identity_document_number, '[[:space:].-]', '')
WHERE profile_identity_document_number IS NOT NULL;
-- Existing successful Staff checks remain authoritative, including accounts with an empty profile identity.
UPDATE users u
JOIN (
    SELECT r.customer_id, c.verified_identity_document, c.identity_verified_at,
           ROW_NUMBER() OVER (PARTITION BY r.customer_id ORDER BY c.identity_verified_at DESC, c.id DESC) AS rn
    FROM check_ins c JOIN reservations r ON r.id = c.reservation_id
    WHERE c.identity_verified_at IS NOT NULL AND c.verified_identity_document REGEXP '^[0-9]{12}$'
) evidence ON evidence.customer_id = u.id AND evidence.rn = 1
SET u.profile_identity_verified_at = evidence.identity_verified_at,
    u.profile_identity_document_number = CASE WHEN u.profile_identity_document_number IS NULL OR u.profile_identity_document_number = ''
        THEN evidence.verified_identity_document ELSE u.profile_identity_document_number END;
UPDATE users SET identity_document_hash = LOWER(SHA2(profile_identity_document_number, 256))
WHERE profile_identity_document_number IS NOT NULL AND profile_identity_document_number <> '';
-- Fail on pre-existing duplicates instead of silently choosing an owner.
CREATE UNIQUE INDEX uk_users_identity_document_hash ON users(identity_document_hash);
ALTER TABLE users MODIFY COLUMN profile_identity_document_number VARCHAR(12) NULL;
