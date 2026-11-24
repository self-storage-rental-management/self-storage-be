CREATE TABLE workflow_sla_metric_runs (
    id TINYINT NOT NULL PRIMARY KEY
);

INSERT INTO workflow_sla_metric_runs (id) VALUES (1);

CREATE TABLE workflow_sla_metrics (
    workflow_type VARCHAR(32) NOT NULL,
    resource_id CHAR(36) NOT NULL,
    facility_id CHAR(36) NOT NULL,
    owner_role VARCHAR(16) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    last_seen_run CHAR(36) NOT NULL,
    closed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (workflow_type, resource_id, started_at),
    KEY idx_workflow_sla_metrics_closed (closed_at)
);
