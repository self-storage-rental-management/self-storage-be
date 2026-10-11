-- Isolated TEST database only; not a registered migration.
CREATE TABLE duong_notification_outbox (
 id VARCHAR(36) PRIMARY KEY,
 recipient_id VARCHAR(36) NOT NULL,
 resource_id VARCHAR(36) NOT NULL,
 kind VARCHAR(32) NOT NULL CHECK (kind='OVERDUE' OR kind='SUPPORT_REVIEW'),
 resolution_event_id VARCHAR(36),
 policy_ref VARCHAR(100) NOT NULL,
 policy_version VARCHAR(100) NOT NULL,
 content VARCHAR(2000) NOT NULL,
 key_hash VARCHAR(64) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK (status='PENDING' OR status='INBOX' OR status='ACKNOWLEDGED'),
 attempts BIGINT NOT NULL CHECK (attempts >= 0),
 retry_at_ms BIGINT NOT NULL,
 notification_id VARCHAR(36) UNIQUE,
 acknowledged_at VARCHAR(40),
 UNIQUE (resource_id,kind,key_hash)
);
CREATE INDEX idx_duong_notice_dispatch ON duong_notification_outbox(status,retry_at_ms,id);
CREATE INDEX idx_duong_notice_review ON duong_notification_outbox(resource_id,kind,resolution_event_id,status);
