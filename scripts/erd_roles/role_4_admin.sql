-- ==============================================================================
-- ROLE 4: ADMIN VIEW (GÓC NHÌN QUẢN TRỊ VIÊN HỆ THỐNG & BẢO MẬT)
-- ==============================================================================
-- Mô tả: Chứa toàn bộ các bảng phục vụ quản trị người dùng, phân quyền RBAC đa cấp,
-- kiểm soát phiên đăng nhập, an ninh truy cập, cấu hình hệ thống và nhật ký kiểm toán.

-- 1. Bảng cơ sở (Reference)
CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL
);

-- 2. Quản lý Người dùng & Phân quyền RBAC (Users, Roles & Permissions)
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    phone VARCHAR(30) NULL,
    permanent_address VARCHAR(500) NULL,
    avatar_url TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    parent_role_id VARCHAR(36) NULL,
    permissions_policy_version INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_roles_parent FOREIGN KEY (parent_role_id) REFERENCES roles(id)
);

CREATE TABLE permissions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_admin_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE role_permissions (
    role_id VARCHAR(36) NOT NULL,
    permission_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_admin_role_perms_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_role_perms_perm FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

CREATE TABLE user_facility_scopes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    scope_level VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, facility_id),
    CONSTRAINT fk_admin_scopes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_admin_scopes_fac FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE CASCADE
);

-- 3. An toàn thông tin, Phiên đăng nhập & Thử thách xác thực (Security & Sessions)
CREATE TABLE sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
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
    CONSTRAINT fk_admin_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE login_history (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NULL,
    email_attempted VARCHAR(320) NOT NULL,
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    device_fingerprint VARCHAR(64) NULL,
    failure_reason VARCHAR(200) NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_login_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE auth_challenges (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    otp_hash VARCHAR(128) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_challenges_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 4. Cấu hình Hệ thống & Nhật ký kiểm toán (System Config & Audit Trail)
CREATE TABLE system_settings (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value VARCHAR(1000) NOT NULL,
    description VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE activity_logs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_id VARCHAR(36) NULL,
    facility_id VARCHAR(36) NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    before_state_json TEXT NULL,
    after_state_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_admin_logs_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE SET NULL
);
