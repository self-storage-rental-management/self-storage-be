param([string]$Database = 'storagehub_local')
$ErrorActionPreference = 'Stop'
$now = [DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
function Q([string]$v) { "'" + $v.Replace("'", "''") + "'" }
function B([string]$v) { "UUID_TO_BIN('$v')" }
$sql = @"
USE $Database; SET NAMES utf8mb4; SET time_zone='+00:00'; START TRANSACTION;
DELETE FROM check_ins WHERE id=UUID_TO_BIN('99999999-0000-0000-0000-000000000001');
DELETE FROM contracts WHERE rental_id=UUID_TO_BIN('aaaaaaaa-0000-0000-0000-000000000001');
DELETE FROM confirmed_rental_periods WHERE rental_id=UUID_TO_BIN('aaaaaaaa-0000-0000-0000-000000000001');
DELETE FROM rental_ledger_accounts WHERE rental_id=UUID_TO_BIN('aaaaaaaa-0000-0000-0000-000000000001');
DELETE FROM rentals WHERE id=UUID_TO_BIN('aaaaaaaa-0000-0000-0000-000000000001');
DELETE FROM reservations WHERE id=UUID_TO_BIN('99999999-0000-0000-0000-000000000001');
DELETE FROM reservation_pricing_snapshots WHERE id=UUID_TO_BIN('88888888-0000-0000-0000-000000000001');
DELETE FROM reservation_quotes WHERE id=UUID_TO_BIN('77777777-0000-0000-0000-000000000001');
INSERT INTO reservation_quotes (id,created_at,updated_at,customer_id,facility_id,unit_type_id,pricing_package_code,policy_version,start_date,end_date,rental_months,monthly_price,subtotal,discount_rate,discount_amount,total_after_discount,reservation_deposit_amount,security_deposit_amount,remaining_rental_amount,due_at_check_in,total_initial_obligation,quoted_at,expires_at,goods_snapshot_json,unit_type_snapshot_json,early_termination_pricing_snapshot_json)
SELECT $(B '77777777-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),u.id,f.id,ut.id,'DEMO-3M','v1','2026-11-01','2027-01-31',3,5500000,16500000,0,0,16500000,0,5500000,16500000,16500000,22000000,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'{}','{}','{}'
FROM users u JOIN facilities f ON f.code='HCM-Q1-F01' JOIN unit_types ut ON ut.facility_id=f.id AND ut.code='S' WHERE u.email='customer@storagehub.demo' LIMIT 1;
INSERT INTO reservations (id,created_at,updated_at,customer_id,facility_id,unit_type_id,assigned_unit_id,source_quote_id,reservation_code,start_date,end_date,status,compatibility_result,goods_review_status,total_goods_volume_m3,total_goods_weight_kg,amount,idempotency_key,version,confirmed_at)
SELECT $(B '99999999-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),q.customer_id,q.facility_id,q.unit_type_id,s.id,$(B '77777777-0000-0000-0000-000000000001'),'DEMO-RENEWAL-TEMPLATE','2026-11-01','2027-01-31','COMPLETED','COMPATIBLE','APPROVED',1,1,16500000,'demo-renewal-template',0,UTC_TIMESTAMP(6)
FROM reservation_quotes q JOIN storage_units s ON s.code='HCM-Q1-F01-S-001' WHERE q.id=$(B '77777777-0000-0000-0000-000000000001');
INSERT INTO reservation_pricing_snapshots (id,created_at,updated_at,reservation_id,pricing_package_code,pricing_policy_version,rental_months,monthly_price,gross_rental_amount,discount_rate,discount_amount,voucher_discount_amount,net_rental_amount,reservation_deposit_amount,security_deposit_amount,remaining_rental_amount,due_at_check_in,total_initial_obligation,quoted_at,expires_at,early_termination_pricing_snapshot_json)
SELECT $(B '88888888-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),r.id,'DEMO-3M','v1',3,5500000,16500000,0,0,0,16500000,0,5500000,16500000,16500000,22000000,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'{}' FROM reservations r WHERE r.id=$(B '99999999-0000-0000-0000-000000000001');
INSERT INTO rentals (id,created_at,updated_at,reservation_id,storage_unit_id,customer_id,facility_id,start_date,contract_end_date,monthly_price,status,inventory_pending,setup_exit_review_required)
SELECT $(B 'aaaaaaaa-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),r.id,r.assigned_unit_id,r.customer_id,r.facility_id,r.start_date,r.end_date,5500000,'active',0,0 FROM reservations r WHERE r.id=$(B '99999999-0000-0000-0000-000000000001');
INSERT INTO contracts (id,created_at,updated_at,contract_number,status,rental_id,signed_at,signed_by)
VALUES ($(B 'bbbbbbbb-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'HD-DEMO-RENEWAL-TEMPLATE','SIGNED',$(B 'aaaaaaaa-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),'KHACH DEMO');
INSERT INTO check_ins (id,created_at,updated_at,reservation_id,rental_id,status,performed_by,scheduled_at,checked_in_at,checklist_json,version,identity_verified_at,verified_identity_document,contract_signed_at,payment_collected_at)
SELECT $(B '99999999-0000-0000-0000-000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),r.id,$(B 'aaaaaaaa-0000-0000-0000-000000000001'),'completed',(SELECT id FROM users WHERE email='staff@storagehub.demo' LIMIT 1),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'{}',0,UTC_TIMESTAMP(6),'CCCD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM reservations r WHERE r.id=$(B '99999999-0000-0000-0000-000000000001');
INSERT INTO confirmed_rental_periods(sequence_no,rental_id,reservation_id,customer_id,facility_id,storage_unit_id,start_date,stored_end_date,end_convention,source_ref,confirmed_at)
SELECT 1,BIN_TO_UUID(r.id),BIN_TO_UUID(r.reservation_id),BIN_TO_UUID(r.customer_id),BIN_TO_UUID(r.facility_id),BIN_TO_UUID(r.storage_unit_id),r.start_date,r.contract_end_date,'INCLUSIVE','local-demo-template',UTC_TIMESTAMP(6) FROM rentals r WHERE r.id=$(B 'aaaaaaaa-0000-0000-0000-000000000001');
INSERT INTO rental_ledger_accounts(rental_id,customer_id,facility_id,revision)
SELECT BIN_TO_UUID(r.id),BIN_TO_UUID(r.customer_id),BIN_TO_UUID(r.facility_id),0 FROM rentals r WHERE r.id=$(B 'aaaaaaaa-0000-0000-0000-000000000001');
COMMIT;
"@
$sql | docker exec -i storagehub-mysql sh -c "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD mysql --default-character-set=utf8mb4 -uroot $Database"
if ($LASTEXITCODE -ne 0) { throw 'Không thể tạo rental nền cho demo gia hạn.' }
Write-Host 'Đã tạo rental nền demo gia hạn.' -ForegroundColor Green
