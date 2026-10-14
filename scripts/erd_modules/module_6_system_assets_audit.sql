-- ==============================================================================
-- MODULE 6: SYSTEM SETTINGS, FILE ASSETS & AUDIT LOGS (CẤU HÌNH, TÀI SẢN & NHẬT KÝ)
-- ==============================================================================

-- Bảng tham chiếu liên kết ngoài (Reference Stubs)
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    full_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL
);

CREATE TABLE facilities (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    code VARCHAR(32) NOT NULL
);

-- Các bảng chính của Phân hệ Quản trị Hệ thống, Tài sản & Kiểm toán
CREATE TABLE system_settings (
    id VARCHAR(36) NOT NULL,
    setting_key VARCHAR(100) NOT NULL,
    setting_value VARCHAR(1000) NOT NULL,
    description VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (setting_key)
);

CREATE TABLE file_assets (
    id VARCHAR(36) NOT NULL,
    uploaded_by VARCHAR(36) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NULL,
    entity_id VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_files_uploader FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

CREATE TABLE notifications (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    type VARCHAR(32) NOT NULL,
    action_url VARCHAR(500) NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE activity_logs (
    id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NULL,
    facility_id VARCHAR(36) NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    before_state_json TEXT NULL,
    after_state_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_system_logs_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE SET NULL
);
