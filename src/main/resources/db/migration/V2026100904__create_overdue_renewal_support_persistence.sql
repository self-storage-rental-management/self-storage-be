-- Persistence tables for the overdue, renewal and support workflow modules.
-- Additive only: no seed data and no changes to existing shared tables.

CREATE TABLE renewal_quotes (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    terms_json LONGTEXT NOT NULL,
    terms_hash VARCHAR(64) NOT NULL,
    quoted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_quotes_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_renewal_quotes_customer FOREIGN KEY (customer_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_accepted_revisions (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    renewal_id BINARY(16) NOT NULL,
    quote_id BINARY(16) NOT NULL,
    revision_number INT NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    note LONGTEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_revision UNIQUE (renewal_id, revision_number),
    CONSTRAINT uk_renewal_accepted_quote UNIQUE (quote_id),
    CONSTRAINT fk_renewal_accepted_revisions_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id),
    CONSTRAINT fk_renewal_accepted_revisions_quote FOREIGN KEY (quote_id) REFERENCES renewal_quotes(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_workflows (
    id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    accepted_revision_id BINARY(16) NOT NULL,
    reviewer_id BINARY(16) NULL,
    reviewed_at TIMESTAMP(6) NULL,
    review_reason VARCHAR(2000) NULL,
    cancellation_reason VARCHAR(2000) NULL,
    payment_deadline TIMESTAMP(6) NULL,
    extension_hold_ref BINARY(16) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_workflows_renewal FOREIGN KEY (id) REFERENCES renewals(id),
    CONSTRAINT fk_renewal_workflows_revision FOREIGN KEY (accepted_revision_id) REFERENCES renewal_accepted_revisions(id),
    CONSTRAINT fk_renewal_workflows_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_open_slots (
    id BINARY(16) NOT NULL,
    renewal_id BINARY(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_open_slot_renewal UNIQUE (renewal_id),
    CONSTRAINT fk_renewal_open_slots_rental FOREIGN KEY (id) REFERENCES rentals(id),
    CONSTRAINT fk_renewal_open_slots_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_idempotency (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_key_hash VARCHAR(64) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    result_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_idempotency UNIQUE (actor_id, operation, request_key_hash),
    CONSTRAINT fk_renewal_idempotency_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_operation_states (
    id BINARY(16) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    deposit_payment_ref BINARY(16) NULL,
    deposit_paid_at TIMESTAMP(6) NULL,
    original_signing_deadline TIMESTAMP(6) NULL,
    effective_signing_deadline TIMESTAMP(6) NULL,
    recovery_cutoff TIMESTAMP(6) NULL,
    appointment_ref BINARY(16) NULL,
    appointment_start TIMESTAMP(6) NULL,
    appointment_end TIMESTAMP(6) NULL,
    arrival_ref BINARY(16) NULL,
    confirmed_exception_ref BINARY(16) NULL,
    pending_exception_ref BINARY(16) NULL,
    completed_by BINARY(16) NULL,
    completed_at TIMESTAMP(6) NULL,
    policy_ref VARCHAR(200) NULL,
    policy_version VARCHAR(100) NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_operation_states_renewal FOREIGN KEY (id) REFERENCES renewals(id)
) ENGINE=InnoDB;

CREATE TABLE renewal_operation_events (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    renewal_id BINARY(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    actor_id BINARY(16) NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_renewal_operation_events_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id)
) ENGINE=InnoDB;

CREATE INDEX idx_renewal_event_timeline
    ON renewal_operation_events (renewal_id, kind, occurred_at);

CREATE TABLE overdue_follow_up_states (
    case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (case_ref)
) ENGINE=InnoDB;

CREATE TABLE overdue_follow_ups (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id BINARY(16) NOT NULL,
    type VARCHAR(24) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    external_ref BINARY(16) NULL,
    policy_ref VARCHAR(200) NULL,
    policy_version VARCHAR(100) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_overdue_follow_ups_rental FOREIGN KEY (rental_id) REFERENCES rentals(id)
) ENGINE=InnoDB;

CREATE INDEX idx_overdue_follow_up_timeline
    ON overdue_follow_ups (case_ref, recorded_at);

CREATE TABLE support_workflow_states (
    id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    assignment_revision BIGINT NOT NULL,
    change_sequence BIGINT NOT NULL,
    accepted_by BINARY(16) NULL,
    assigned_at TIMESTAMP(6) NULL,
    accepted_at TIMESTAMP(6) NULL,
    resolved_by BINARY(16) NULL,
    resolved_at TIMESTAMP(6) NULL,
    closed_at TIMESTAMP(6) NULL,
    last_public_staff_reply_at TIMESTAMP(6) NULL,
    first_public_staff_reply_at TIMESTAMP(6) NULL,
    parent_ticket_id BINARY(16) NULL,
    linked_type VARCHAR(32) NULL,
    linked_id BINARY(16) NULL,
    changed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_workflow_states_ticket FOREIGN KEY (id) REFERENCES support_tickets(id)
) ENGINE=InnoDB;

CREATE TABLE support_messages (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    author_role VARCHAR(16) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    evidence_json VARCHAR(500) NOT NULL,
    sent_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
) ENGINE=InnoDB;

CREATE TABLE support_workflow_events (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor_id BINARY(16) NULL,
    assigned_staff_id BINARY(16) NULL,
    assignment_revision BIGINT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    recorded_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_workflow_events_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
) ENGINE=InnoDB;

CREATE TABLE support_escalations (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    target_module VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    decision_reason VARCHAR(2000) NULL,
    requested_by BINARY(16) NULL,
    decided_by BINARY(16) NULL,
    receiver_ref BINARY(16) NULL,
    requested_at TIMESTAMP(6) NULL,
    decided_at TIMESTAMP(6) NULL,
    evidence_json VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_escalations_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
) ENGINE=InnoDB;

CREATE TABLE support_command_receipts (
    id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_actor_command_key UNIQUE (actor_id, operation, key_hash)
) ENGINE=InnoDB;
