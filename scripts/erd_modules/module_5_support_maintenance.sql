-- ==============================================================================
-- MODULE 5: CUSTOMER SUPPORT & FACILITY MAINTENANCE (CSKH & BẢO TRÌ)
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

CREATE TABLE storage_units (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    code VARCHAR(64) NOT NULL
);

CREATE TABLE return_cases (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    status VARCHAR(32) NOT NULL
);

-- Các bảng chính của Phân hệ Hỗ trợ CSKH & Bảo trì
CREATE TABLE support_tickets (
    id VARCHAR(36) NOT NULL,
    ticket_code VARCHAR(32) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    assigned_to VARCHAR(36) NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (ticket_code),
    CONSTRAINT fk_support_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_support_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_support_staff FOREIGN KEY (assigned_to) REFERENCES users(id)
);

CREATE TABLE support_ticket_messages (
    id VARCHAR(36) NOT NULL,
    ticket_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_author FOREIGN KEY (author_id) REFERENCES users(id)
);

CREATE TABLE support_messages (
    id VARCHAR(36) NOT NULL,
    ticket_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    author_role VARCHAR(16) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    evidence_json VARCHAR(500) NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_msg_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_msg_author FOREIGN KEY (author_id) REFERENCES users(id)
);

CREATE TABLE support_escalations (
    id VARCHAR(36) NOT NULL,
    ticket_id VARCHAR(36) NOT NULL,
    target_module VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    decision_reason VARCHAR(2000) NULL,
    requested_by VARCHAR(36) NULL,
    decided_by VARCHAR(36) NULL,
    receiver_ref VARCHAR(36) NULL,
    requested_at TIMESTAMP NULL,
    decided_at TIMESTAMP NULL,
    evidence_json VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_esc_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_esc_requester FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT fk_support_esc_decider FOREIGN KEY (decided_by) REFERENCES users(id)
);

CREATE TABLE support_workflow_states (
    id VARCHAR(36) NOT NULL,
    parent_ticket_id VARCHAR(36) NULL,
    assignment_revision BIGINT NOT NULL DEFAULT 0,
    change_sequence BIGINT NOT NULL DEFAULT 0,
    accepted_by VARCHAR(36) NULL,
    assigned_at TIMESTAMP NULL,
    accepted_at TIMESTAMP NULL,
    resolved_by VARCHAR(36) NULL,
    resolved_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    last_public_staff_reply_at TIMESTAMP NULL,
    first_public_staff_reply_at TIMESTAMP NULL,
    linked_type VARCHAR(32) NULL,
    linked_id VARCHAR(36) NULL,
    changed_at TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_wf_state_ticket FOREIGN KEY (id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_wf_state_acceptor FOREIGN KEY (accepted_by) REFERENCES users(id),
    CONSTRAINT fk_support_wf_state_resolver FOREIGN KEY (resolved_by) REFERENCES users(id)
);

CREATE TABLE support_workflow_events (
    id VARCHAR(36) NOT NULL,
    ticket_id VARCHAR(36) NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NULL,
    assigned_staff_id VARCHAR(36) NULL,
    assignment_revision BIGINT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    recorded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_wf_event_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_wf_event_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_support_wf_event_staff FOREIGN KEY (assigned_staff_id) REFERENCES users(id)
);

CREATE TABLE support_command_receipts (
    id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (actor_id, operation, key_hash),
    CONSTRAINT fk_support_cmd_actor FOREIGN KEY (actor_id) REFERENCES users(id)
);

CREATE TABLE maintenance_tasks (
    id VARCHAR(36) NOT NULL,
    facility_id VARCHAR(36) NOT NULL,
    storage_unit_id VARCHAR(36) NOT NULL,
    return_case_id VARCHAR(36) NULL,
    title VARCHAR(255) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    damage_classification VARCHAR(32) NULL,
    reported_by VARCHAR(36) NOT NULL,
    assigned_to VARCHAR(36) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    due_at DATE NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    result_report VARCHAR(4000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_maintenance_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_maintenance_unit FOREIGN KEY (storage_unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_maintenance_return_case FOREIGN KEY (return_case_id) REFERENCES return_cases(id),
    CONSTRAINT fk_maintenance_reporter FOREIGN KEY (reported_by) REFERENCES users(id),
    CONSTRAINT fk_maintenance_assignee FOREIGN KEY (assigned_to) REFERENCES users(id)
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
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

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
    CONSTRAINT fk_logs_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_logs_facility FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE SET NULL
);
