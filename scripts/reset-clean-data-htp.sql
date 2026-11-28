-- StorageHub Reset & Clean Data Script
-- Clears all transactional data, orders, reservations, check-ins, payments
-- Creates facility "Kho Hồ Thanh Phương" (HTP-F01)
-- Sets up scopes/permissions for all demo accounts

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. Truncate all transactional tables
TRUNCATE TABLE access_credentials;
TRUNCATE TABLE activity_logs;
TRUNCATE TABLE auth_challenges;
TRUNCATE TABLE booking_documents;
TRUNCATE TABLE check_ins;
TRUNCATE TABLE contracts;
TRUNCATE TABLE customer_chat_sessions;
TRUNCATE TABLE damage_incidents;
TRUNCATE TABLE facility_lifecycle_change_requests;
TRUNCATE TABLE login_history;
TRUNCATE TABLE maintenance_tasks;
TRUNCATE TABLE notification_outbox;
TRUNCATE TABLE notifications;
TRUNCATE TABLE overdue_follow_up_states;
TRUNCATE TABLE overdue_follow_ups;
TRUNCATE TABLE payment_complaints;
TRUNCATE TABLE payment_receipt_emails;
TRUNCATE TABLE payments;
TRUNCATE TABLE recovery_overdue_fee_assessments;
TRUNCATE TABLE refund_requests;
TRUNCATE TABLE refunds;
TRUNCATE TABLE registration_captchas;
TRUNCATE TABLE renewal_accepted_revisions;
TRUNCATE TABLE renewal_calendar_staff_locks;
TRUNCATE TABLE renewal_extension_holds;
TRUNCATE TABLE renewal_idempotency;
TRUNCATE TABLE renewal_installment_notices;
TRUNCATE TABLE renewal_installment_payment_bindings;
TRUNCATE TABLE renewal_installment_payment_quotes;
TRUNCATE TABLE renewal_open_slots;
TRUNCATE TABLE renewal_operation_events;
TRUNCATE TABLE renewal_operation_states;
TRUNCATE TABLE renewal_quotes;
TRUNCATE TABLE renewal_signing_reservations;
TRUNCATE TABLE renewal_workflows;
TRUNCATE TABLE renewals;
TRUNCATE TABLE rental_ledger_accounts;
TRUNCATE TABLE rental_ledger_allocations;
TRUNCATE TABLE rental_ledger_commands;
TRUNCATE TABLE rental_ledger_due_changes;
TRUNCATE TABLE rental_ledger_gateway_evidence;
TRUNCATE TABLE rental_ledger_obligations;
TRUNCATE TABLE rental_ledger_receipts;
TRUNCATE TABLE rental_ledger_refunds;
TRUNCATE TABLE rentals;
TRUNCATE TABLE reservation_email_verifications;
TRUNCATE TABLE reservation_goods_items;
TRUNCATE TABLE reservation_pricing_snapshots;
TRUNCATE TABLE reservation_quotes;
TRUNCATE TABLE reservations;
TRUNCATE TABLE return_cases;
TRUNCATE TABLE return_financial_snapshot_history;
TRUNCATE TABLE return_financial_snapshots;
TRUNCATE TABLE return_ledger_allocations;
TRUNCATE TABLE return_settlement_receipts;
TRUNCATE TABLE sessions;
TRUNCATE TABLE support_command_receipts;
TRUNCATE TABLE support_escalations;
TRUNCATE TABLE support_hidden_history;
TRUNCATE TABLE support_message_reads;
TRUNCATE TABLE support_messages;
TRUNCATE TABLE support_ticket_messages;
TRUNCATE TABLE support_tickets;
TRUNCATE TABLE support_workflow_events;
TRUNCATE TABLE support_workflow_states;
TRUNCATE TABLE unit_assignments;
TRUNCATE TABLE unit_type_change_requests;

-- 2. Remove temporary test facilities (GEMINI-01, FPT-GEMINI-GPT)
DELETE FROM storage_units WHERE facility_id IN (SELECT id FROM facilities WHERE code IN ('GEMINI-01', 'FPT-GEMINI-GPT'));
DELETE FROM rental_package_policies WHERE facility_id IN (SELECT id FROM facilities WHERE code IN ('GEMINI-01', 'FPT-GEMINI-GPT'));
DELETE FROM user_facility_scopes WHERE facility_id IN (SELECT id FROM facilities WHERE code IN ('GEMINI-01', 'FPT-GEMINI-GPT'));
DELETE FROM unit_types WHERE facility_id IN (SELECT id FROM facilities WHERE code IN ('GEMINI-01', 'FPT-GEMINI-GPT'));
DELETE FROM facilities WHERE code IN ('GEMINI-01', 'FPT-GEMINI-GPT');

-- 3. Reset existing storage units to available
UPDATE storage_units SET status = 'available', version = 0;

-- 4. Create or update facility "Kho Hồ Thanh Phương"
INSERT INTO facilities (id, created_at, updated_at, code, name, address, city, phone, access_hours, image_url, climate_controlled, security_description, status)
VALUES (
    UUID_TO_BIN('11111111-1111-1111-1111-111111111115'),
    NOW(6),
    NOW(6),
    'HTP-F01',
    'Kho Hồ Thanh Phương',
    'Lô E2a-7, Đường D1, Khu Công nghệ cao, Phường Long Thạnh Mỹ, TP. Thủ Đức, TP. Hồ Chí Minh',
    'TP. Hồ Chí Minh',
    '+84 908 123 456',
    '24/7',
    '/images/facilities/binh-duong.jpg',
    0,
    NULL,
    'active'
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    address = VALUES(address),
    city = VALUES(city),
    phone = VALUES(phone),
    access_hours = VALUES(access_hours),
    image_url = VALUES(image_url),
    climate_controlled = VALUES(climate_controlled),
    security_description = VALUES(security_description),
    status = 'active';

-- 5. Rental package policies for Kho Hồ Thanh Phương
INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, free_setup_days, discount_rate, deposit_rate, early_termination_fee_cap_months, early_termination_fee_coefficient, early_termination_notice_days, policy_version, publication_status, active, effective_from)
VALUES
    (UUID_TO_BIN('66666666-6666-6666-0013-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'DEMO_3M', 'Gói thuê 3 tháng', 3, 3, 0.0000, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0014-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'DEMO_6M', 'Gói thuê 6 tháng', 6, 7, 0.0500, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0015-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'DEMO_12M', 'Gói thuê 12 tháng', 12, 15, 0.1000, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0016-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'DEMO_24M', 'Gói thuê 24 tháng', 24, 30, 0.1500, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01')
ON DUPLICATE KEY UPDATE name=VALUES(name), free_setup_days=VALUES(free_setup_days), discount_rate=VALUES(discount_rate), deposit_rate=VALUES(deposit_rate), policy_version=VALUES(policy_version), publication_status=VALUES(publication_status), active=VALUES(active);

-- 6. User facility scopes for all accounts to access Kho Hồ Thanh Phương
DELETE FROM user_facility_scopes WHERE facility_id != UUID_TO_BIN('11111111-1111-1111-1111-111111111115');

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0001-000000000005'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'READ'
FROM users u WHERE u.email='customer@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level=VALUES(scope_level);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0002-000000000005'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'OPERATE'
FROM users u WHERE u.email='staff@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level=VALUES(scope_level);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0003-000000000005'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'MANAGE'
FROM users u WHERE u.email='manager@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level=VALUES(scope_level);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0004-000000000005'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'MANAGE'
FROM users u WHERE u.email='business@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level=VALUES(scope_level);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0005-000000000005'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111115'), 'MANAGE'
FROM users u WHERE u.email='admin@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level=VALUES(scope_level);

SET FOREIGN_KEY_CHECKS = 1;
