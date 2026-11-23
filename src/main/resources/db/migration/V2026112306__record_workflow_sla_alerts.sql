CREATE TABLE workflow_sla_alerts (
    workflow_type VARCHAR(32) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    milestone VARCHAR(12) NOT NULL,
    recipient_id BINARY(16) NOT NULL,
    sent_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (workflow_type, resource_id, due_at, milestone, recipient_id)
);
