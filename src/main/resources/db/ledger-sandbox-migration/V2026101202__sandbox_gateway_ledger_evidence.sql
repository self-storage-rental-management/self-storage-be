-- MySQL CREATE TABLE LIKE can rename CHECK symbols. Locate the existing checks by their columns.
SET @sandbox_method_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='rental_ledger_receipts' AND tc.CONSTRAINT_TYPE='CHECK'
 AND cc.CHECK_CLAUSE LIKE '%method%' AND cc.CHECK_CLAUSE NOT LIKE '%evidence%' LIMIT 1);
SET @sandbox_alter = IF(@sandbox_method_check IS NULL,'SELECT 1',CONCAT('ALTER TABLE rental_ledger_receipts DROP CHECK `',REPLACE(@sandbox_method_check,'`','``'),'`'));
PREPARE sandbox_stmt FROM @sandbox_alter;
EXECUTE sandbox_stmt;
DEALLOCATE PREPARE sandbox_stmt;
SET @sandbox_file_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='rental_ledger_receipts' AND tc.CONSTRAINT_TYPE='CHECK'
 AND cc.CHECK_CLAUSE LIKE '%evidence_file_uuid%' AND cc.CHECK_CLAUSE NOT LIKE '%method%' LIMIT 1);
SET @sandbox_alter = IF(@sandbox_file_check IS NULL,'SELECT 1',CONCAT('ALTER TABLE rental_ledger_receipts DROP CHECK `',REPLACE(@sandbox_file_check,'`','``'),'`'));
PREPARE sandbox_stmt FROM @sandbox_alter;
EXECUTE sandbox_stmt;
DEALLOCATE PREPARE sandbox_stmt;
ALTER TABLE rental_ledger_receipts MODIFY evidence_file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE rental_ledger_receipts ADD CONSTRAINT ck_rlr_method CHECK (method IN ('CASH','VERIFIED_BANK','SIMULATED','VNPAY_SANDBOX'));
ALTER TABLE rental_ledger_receipts ADD CONSTRAINT ck_rlr_uuid CHECK (actor_uuid IS NOT NULL AND
 ((method='VNPAY_SANDBOX' AND evidence_file_id IS NULL) OR (method<>'VNPAY_SANDBOX' AND evidence_file_uuid IS NOT NULL)));
CREATE TABLE rental_ledger_gateway_evidence (
 receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 proof_log_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 environment VARCHAR(16) NOT NULL CHECK (environment='SANDBOX'),
 transaction_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 payment_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(payment_id,'-',''))) STORED,
 proof_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(proof_log_id,'-',''))) STORED,
 CONSTRAINT ck_rlge_uuid CHECK (payment_uuid IS NOT NULL AND proof_uuid IS NOT NULL),
 CONSTRAINT fk_rlge_receipt FOREIGN KEY(receipt_id) REFERENCES rental_ledger_receipts(id),
 CONSTRAINT fk_rlge_payment FOREIGN KEY(payment_uuid) REFERENCES payments(id),
 CONSTRAINT fk_rlge_proof FOREIGN KEY(proof_uuid) REFERENCES activity_logs(id)
) ENGINE=InnoDB;
