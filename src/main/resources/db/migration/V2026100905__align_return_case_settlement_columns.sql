-- Align the initial return-case settlement column names with ReturnCase.
-- Renames are conditional so an already-correct schema is left untouched.

SET @storagehub_return_case_deposit_old = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'deposit_deduction'
);
SET @storagehub_return_case_deposit_new = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'deposit_amount'
);
SET @storagehub_rename_return_case_deposit = IF(
    @storagehub_return_case_deposit_old = 1 AND @storagehub_return_case_deposit_new = 0,
    'ALTER TABLE return_cases CHANGE COLUMN deposit_deduction deposit_amount DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_return_case_deposit;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_return_case_outstanding_old = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'outstanding_amount'
);
SET @storagehub_return_case_outstanding_new = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'outstanding_fee'
);
SET @storagehub_rename_return_case_outstanding = IF(
    @storagehub_return_case_outstanding_old = 1 AND @storagehub_return_case_outstanding_new = 0,
    'ALTER TABLE return_cases CHANGE COLUMN outstanding_amount outstanding_fee DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_return_case_outstanding;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_return_case_refund_old = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'net_refund'
);
SET @storagehub_return_case_refund_new = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'net_refund_amount'
);
SET @storagehub_rename_return_case_refund = IF(
    @storagehub_return_case_refund_old = 1 AND @storagehub_return_case_refund_new = 0,
    'ALTER TABLE return_cases CHANGE COLUMN net_refund net_refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_return_case_refund;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;

SET @storagehub_return_case_due_old = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'amount_due'
);
SET @storagehub_return_case_due_new = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'return_cases'
      AND column_name = 'amount_due_from_customer'
);
SET @storagehub_rename_return_case_due = IF(
    @storagehub_return_case_due_old = 1 AND @storagehub_return_case_due_new = 0,
    'ALTER TABLE return_cases CHANGE COLUMN amount_due amount_due_from_customer DECIMAL(14,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE storagehub_stmt FROM @storagehub_rename_return_case_due;
EXECUTE storagehub_stmt;
DEALLOCATE PREPARE storagehub_stmt;
