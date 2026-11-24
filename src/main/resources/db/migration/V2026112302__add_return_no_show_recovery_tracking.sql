ALTER TABLE return_cases
    ADD COLUMN recovery_eligible_at TIMESTAMP(6) NULL,
    ADD COLUMN no_show_at TIMESTAMP(6) NULL,
    ADD COLUMN recovery_task_created_at TIMESTAMP(6) NULL,
    ADD COLUMN recovery_notes TEXT NULL;

UPDATE return_cases
SET recovery_eligible_at = DATE_ADD(
    DATE_ADD(CAST(scheduled_date AS DATETIME), INTERVAL 1 DAY),
    INTERVAL 24 HOUR
)
WHERE recovery_eligible_at IS NULL;

CREATE INDEX idx_return_cases_recovery_due
    ON return_cases (status, recovery_eligible_at, recovery_task_created_at);
