CREATE TABLE processing_leases (
    reservation_id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NULL,
    session_id BINARY(16) NULL,
    token BINARY(16) NULL,
    expires_at DATETIME(6) NULL,
    revision BIGINT NOT NULL DEFAULT 0
);
