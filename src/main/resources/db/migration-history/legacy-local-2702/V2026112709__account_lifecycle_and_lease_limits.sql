ALTER TABLE users
    ADD COLUMN temporary_password_expires_at TIMESTAMP(6) NULL,
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMP(6) NULL;

ALTER TABLE processing_leases
    ADD COLUMN acquired_at DATETIME(6) NULL,
    ADD COLUMN max_expires_at DATETIME(6) NULL;
