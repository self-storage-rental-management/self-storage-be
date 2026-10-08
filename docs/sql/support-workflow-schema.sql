-- REVIEW ONLY, 08/10/2026. Not an enabled migration; not executed against local/shared DB.
-- Confirm UUID, timestamp, naming and collation on the actual MySQL/TiDB schema before approval.
-- No ALTER of shared tables, seed, DELETE, backfill or implicit permission grant.
CREATE TABLE support_workflow_states (
 id BINARY(16) PRIMARY KEY, version BIGINT NOT NULL,
 assignment_revision BIGINT NOT NULL, change_sequence BIGINT NOT NULL,
 accepted_by BINARY(16) NULL, assigned_at DATETIME(6) NULL, accepted_at DATETIME(6) NULL,
 resolved_by BINARY(16) NULL, resolved_at DATETIME(6) NULL, closed_at DATETIME(6) NULL,
 last_public_staff_reply_at DATETIME(6) NULL, first_public_staff_reply_at DATETIME(6) NULL,
 parent_ticket_id BINARY(16) NULL, linked_type VARCHAR(32) NULL, linked_id BINARY(16) NULL,
 changed_at DATETIME(6) NULL,
 FOREIGN KEY (id) REFERENCES support_tickets(id)
);
CREATE TABLE support_messages (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 ticket_id BINARY(16) NOT NULL, author_id BINARY(16) NOT NULL,
 author_role VARCHAR(16) NOT NULL, visibility VARCHAR(16) NOT NULL,
 body VARCHAR(4000) NOT NULL, evidence_json VARCHAR(500) NOT NULL, sent_at DATETIME(6) NOT NULL,
 FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
);
CREATE TABLE support_workflow_events (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 ticket_id BINARY(16) NOT NULL, type VARCHAR(32) NOT NULL,
 actor_id BINARY(16) NULL, assigned_staff_id BINARY(16) NULL, assignment_revision BIGINT NOT NULL,
 reason VARCHAR(2000) NOT NULL, recorded_at DATETIME(6) NULL,
 FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
);
CREATE TABLE support_escalations (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 ticket_id BINARY(16) NOT NULL, target_module VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
 reason VARCHAR(2000) NOT NULL, decision_reason VARCHAR(2000) NULL,
 requested_by BINARY(16) NULL, decided_by BINARY(16) NULL, receiver_ref BINARY(16) NULL,
 requested_at DATETIME(6) NULL, decided_at DATETIME(6) NULL, evidence_json VARCHAR(500) NOT NULL,
 FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
);
CREATE TABLE support_command_receipts (
 id BINARY(16) PRIMARY KEY, actor_id BINARY(16) NOT NULL, operation VARCHAR(32) NOT NULL,
 key_hash VARCHAR(64) NOT NULL, payload_hash VARCHAR(64) NOT NULL, result_json TEXT NOT NULL,
 UNIQUE KEY uk_support_actor_command_key (actor_id,operation,key_hash)
);
