-- Do not infer execution dates for historical approvals or gateway acknowledgements.
ALTER TABLE refunds ADD COLUMN executed_at DATETIME(6) NULL;
