SET @handover_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='check_ins' AND column_name='handover_record_json')=0, 'ALTER TABLE check_ins ADD COLUMN handover_record_json LONGTEXT NULL', 'SELECT 1');
PREPARE handover_statement FROM @handover_ddl;
EXECUTE handover_statement;
DEALLOCATE PREPARE handover_statement;
SET @handover_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='check_ins' AND column_name='customer_receipt_confirmed_at')=0, 'ALTER TABLE check_ins ADD COLUMN customer_receipt_confirmed_at DATETIME(6) NULL', 'SELECT 1');
PREPARE handover_statement FROM @handover_ddl;
EXECUTE handover_statement;
DEALLOCATE PREPARE handover_statement;
