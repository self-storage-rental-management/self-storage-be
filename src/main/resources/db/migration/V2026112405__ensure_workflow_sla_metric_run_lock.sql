-- Existing databases may already have the lock table but miss its seed row
-- after the workflow SLA migrations were reconciled across branches.
INSERT INTO workflow_sla_metric_runs (id)
VALUES (1)
ON DUPLICATE KEY UPDATE id = id;
