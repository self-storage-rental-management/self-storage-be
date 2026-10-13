-- ==============================================================================
-- MODULE 1: AUTHENTICATION, USERS & RBAC PERMISSIONS (NGƯỜI DÙNG & PHÂN QUYỀN)
-- ==============================================================================

CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    phone VARCHAR(30) NULL,
    permanent_address VARCHAR(500) NULL,
    emergency_contact_name VARCHAR(160) NULL,
    emergency_contact_phone VARCHAR(30) NULL,
    avatar_url TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (email)
);

CREATE TABLE roles (
    id VARCHAR(36) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parent_role_id VARCHAR(36) NULL,
    permissions_policy_version INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (code),
    CONSTRAINT fk_roles_parent FOREIGN KEY (parent_role_id) REFERENCES roles(id)
);

CREATE TABLE permissions (
    id VARCHAR(36) NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (code)
);

CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE role_permissions (
    role_id VARCHAR(36) NOT NULL,
    permission_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

CREATE TABLE user_facility_scopes (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    scope_level VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (user_id, facility_id),
    CONSTRAINT fk_user_scopes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE sessions (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(128) NULL,
    refresh_token_hash VARCHAR(128) NULL,
    expires_at TIMESTAMP NOT NULL,
    refresh_expires_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE login_history (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NULL,
    email_attempted VARCHAR(320) NOT NULL,
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    device_fingerprint VARCHAR(64) NULL,
    failure_reason VARCHAR(200) NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_login_history_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE auth_challenges (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (token_hash),
    CONSTRAINT fk_auth_challenges_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
