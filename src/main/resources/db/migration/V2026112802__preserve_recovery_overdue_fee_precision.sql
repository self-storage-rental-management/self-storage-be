-- Recovery fee calculations and return cases preserve two decimal places.
ALTER TABLE recovery_overdue_fee_assessments
    MODIFY COLUMN accrued_amount DECIMAL(22,2) NOT NULL,
    MODIFY COLUMN outstanding_amount DECIMAL(22,2) NOT NULL,
    MODIFY COLUMN unposted_amount DECIMAL(22,2) NOT NULL;
