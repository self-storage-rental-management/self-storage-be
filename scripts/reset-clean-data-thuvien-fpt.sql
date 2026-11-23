-- StorageHub Reset & Clean Database Script
-- 1. Clears all transactional data, reservations, rentals, contracts, check-ins, returns, payments, tickets, logs
-- 2. Creates facility "Kho Thư viện FPT" (FPT-TV-F01)
-- 3. Sets up unit types (S, M, L, XL), storage units (20 units, all available), and rental packages
-- 4. Assigns all demo accounts (Admin, Business, Manager, Staff, Customer) to this facility

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
USE storagehub_local;
SET FOREIGN_KEY_CHECKS = 0;
START TRANSACTION;

-- ==============================================================================
-- 1. DELETE ALL TRANSACTIONAL & OPERATIONAL DATA
-- ==============================================================================
DELETE FROM access_credentials;
DELETE FROM activity_logs;
DELETE FROM auth_challenges;
DELETE FROM booking_documents;
DELETE FROM business_record_codes;
DELETE FROM business_record_code_sequences;
DELETE FROM check_ins;
DELETE FROM confirmed_rental_periods;
DELETE FROM contracts;
DELETE FROM customer_chat_sessions;
DELETE FROM damage_incidents;
DELETE FROM facility_lifecycle_change_requests;
DELETE FROM file_assets;
DELETE FROM login_history;
DELETE FROM maintenance_tasks;
DELETE FROM notification_outbox;
DELETE FROM notifications;
DELETE FROM overdue_follow_up_states;
DELETE FROM overdue_follow_ups;
DELETE FROM payment_complaints;
DELETE FROM payment_receipt_emails;
DELETE FROM payments;
DELETE FROM recovery_overdue_fee_assessments;
DELETE FROM refund_requests;
DELETE FROM refunds;
DELETE FROM registration_captchas;
DELETE FROM renewal_accepted_revisions;
DELETE FROM renewal_calendar_staff_locks;
DELETE FROM renewal_extension_holds;
DELETE FROM renewal_idempotency;
DELETE FROM renewal_installment_notices;
DELETE FROM renewal_installment_payment_bindings;
DELETE FROM renewal_installment_payment_quotes;
DELETE FROM renewal_open_slots;
DELETE FROM renewal_operation_events;
DELETE FROM renewal_operation_states;
DELETE FROM renewal_quotes;
DELETE FROM renewal_signing_reservations;
DELETE FROM renewal_workflows;
DELETE FROM renewals;
DELETE FROM rental_ledger_accounts;
DELETE FROM rental_ledger_allocations;
DELETE FROM rental_ledger_commands;
DELETE FROM rental_ledger_due_changes;
DELETE FROM rental_ledger_gateway_evidence;
DELETE FROM rental_ledger_obligations;
DELETE FROM rental_ledger_receipts;
DELETE FROM rental_ledger_refunds;
DELETE FROM rentals;
DELETE FROM request_rate_limits;
DELETE FROM reservation_email_verifications;
DELETE FROM reservation_goods_items;
DELETE FROM reservation_pricing_snapshots;
DELETE FROM reservation_quotes;
DELETE FROM reservations;
DELETE FROM return_cases;
DELETE FROM return_financial_snapshot_history;
DELETE FROM return_financial_snapshots;
DELETE FROM return_ledger_allocations;
DELETE FROM return_settlement_receipts;
DELETE FROM sessions;
DELETE FROM support_command_receipts;
DELETE FROM support_escalations;
DELETE FROM support_hidden_history;
DELETE FROM support_message_reads;
DELETE FROM support_messages;
DELETE FROM support_ticket_messages;
DELETE FROM support_tickets;
DELETE FROM support_workflow_events;
DELETE FROM support_workflow_states;
DELETE FROM unit_assignments;
DELETE FROM unit_type_change_requests;

-- Reset reservation code sequence back to 1
DELETE FROM reservation_code_sequences;
INSERT INTO reservation_code_sequences (id, next_value) VALUES (1, 1);

-- ==============================================================================
-- 2. CLEAN UP OLD INVENTORY & FACILITIES
-- ==============================================================================
DELETE FROM storage_units;
DELETE FROM rental_package_policies;
DELETE FROM user_facility_scopes;
DELETE FROM unit_types;
DELETE FROM facilities;
DELETE FROM facility_policy_catalog;

-- Clean non-core test users
DELETE ur FROM user_roles ur JOIN users u ON u.id = ur.user_id
WHERE u.email NOT IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);
DELETE FROM users WHERE email NOT IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);

-- Reset user profile states
UPDATE users
SET avatar_url = NULL,
    emergency_contact_name = NULL,
    emergency_contact_phone = NULL,
    permanent_address = NULL,
    authz_version = 0
WHERE email IN (
  'admin@storagehub.demo',
  'business@storagehub.demo',
  'manager@storagehub.demo',
  'staff@storagehub.demo',
  'customer@storagehub.demo'
);

-- ==============================================================================
-- 3. CREATE FACILITY: "Kho Thư viện FPT" (FPT-TV-F01)
-- ==============================================================================
INSERT INTO facilities (
    id, created_at, updated_at, code, name, address, city, phone, access_hours, image_url, climate_controlled, security_description, status
) VALUES (
    UUID_TO_BIN('11111111-1111-1111-1111-111111111116'),
    NOW(6),
    NOW(6),
    'FPT-TV-F01',
    'Kho Thư viện FPT',
    'Khuôn viên Thư viện FPT, Lô E2a-7, Đường D1, Khu Công nghệ cao, Phường Long Thạnh Mỹ, TP. Thủ Đức, TP. Hồ Chí Minh',
    'TP. Hồ Chí Minh',
    '+84 28 7300 5588',
    '24/7',
    '/images/customer-units/kho-s.jpg',
    1,
    'Bảo vệ 24/7, camera giám sát CCTV toàn khu, thẻ từ kiểm soát ra vào và hệ thống PCCC tiêu chuẩn',
    'active'
);

-- ==============================================================================
-- 4. CREATE UNIT TYPES FOR "Kho Thư viện FPT" (S, M, L, XL)
-- ==============================================================================
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name, length_m, width_m, height_m,
    monthly_price, image_url, detail_image_url, max_load_kg, rack_count,
    rack_length_m, rack_width_m, rack_height_m, status, published_revision, accepted_goods_json, version
) VALUES
(
    UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'S', 'Kho Nhỏ (S)',
    8.00, 10.00, 5.00, 1000000.00,
    '/images/customer-units/kho-s.jpg', '/images/customer-units/details/kho-s-chi-tiet.png',
    600.00, 4, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'M', 'Kho Trung (M)',
    12.60, 10.40, 5.00, 2000000.00,
    '/images/customer-units/kho-m.jpg', '/images/customer-units/details/kho-m-chi-tiet.png',
    1200.00, 6, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'L', 'Kho Lớn (L)',
    18.30, 10.80, 5.00, 3000000.00,
    '/images/customer-units/kho-l.jpg', '/images/customer-units/details/kho-l-chi-tiet.png',
    2400.00, 8, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'XL', 'Kho Rất Lớn (XL)',
    25.00, 11.20, 5.00, 4000000.00,
    '/images/customer-units/kho-xl.jpg', '/images/customer-units/details/kho-xl-chi-tiet.png',
    3600.00, 10, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
);

-- Allow unit types to accept active goods categories and materials
UPDATE unit_types
SET accepted_goods_json = JSON_OBJECT(
    'categoryIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_type_catalog WHERE status='ACTIVE'), JSON_ARRAY()),
    'materialIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_material_catalog WHERE status='ACTIVE'), JSON_ARRAY())
)
WHERE facility_id = UUID_TO_BIN('11111111-1111-1111-1111-111111111116');

-- ==============================================================================
-- 5. CREATE RENTAL PACKAGE POLICIES
-- ==============================================================================
INSERT INTO rental_package_policies (
    id, created_at, updated_at, facility_id, code, name, rental_months, free_setup_days,
    discount_rate, deposit_rate, early_termination_fee_cap_months, early_termination_fee_coefficient,
    early_termination_notice_days, policy_version, publication_status, active, effective_from
) VALUES
(UUID_TO_BIN('66666666-6666-6666-0017-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'DEMO_3M', 'Gói thuê 1', 3, 3, 0.0000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0018-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'DEMO_6M', 'Gói thuê 2', 6, 7, 0.0500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0019-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'DEMO_12M', 'Gói thuê 3', 12, 15, 0.1000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0020-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'DEMO_24M', 'Gói thuê 4', 24, 30, 0.1500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01');

-- ==============================================================================
-- 6. CREATE STORAGE UNITS (20 GIAN KHO, TẤT CẢ SẴN SÀNG CHO THUÊ)
-- ==============================================================================
INSERT INTO storage_units (id, created_at, updated_at, facility_id, unit_type_id, code, floor, zone, status, version)
VALUES
    -- 5 Kho Nhỏ (S)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000081'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), 'FPT-TV-F01-S-001', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000082'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), 'FPT-TV-F01-S-002', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000083'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), 'FPT-TV-F01-S-003', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000084'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), 'FPT-TV-F01-S-004', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000085'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0017-000000000001'), 'FPT-TV-F01-S-005', '1', 'Khu A', 'available', 0),

    -- 5 Kho Trung (M)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000086'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), 'FPT-TV-F01-M-001', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000087'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), 'FPT-TV-F01-M-002', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000088'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), 'FPT-TV-F01-M-003', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000089'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), 'FPT-TV-F01-M-004', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000090'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0018-000000000001'), 'FPT-TV-F01-M-005', '2', 'Khu B', 'available', 0),

    -- 5 Kho Lớn (L)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000091'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), 'FPT-TV-F01-L-001', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000092'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), 'FPT-TV-F01-L-002', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000093'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), 'FPT-TV-F01-L-003', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000094'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), 'FPT-TV-F01-L-004', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000095'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0019-000000000001'), 'FPT-TV-F01-L-005', '3', 'Khu C', 'available', 0),

    -- 5 Kho Rất Lớn (XL)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000096'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), 'FPT-TV-F01-XL-001', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000097'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), 'FPT-TV-F01-XL-002', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000098'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), 'FPT-TV-F01-XL-003', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000099'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), 'FPT-TV-F01-XL-004', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000100'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), UUID_TO_BIN('22222222-2222-2222-0020-000000000001'), 'FPT-TV-F01-XL-005', '4', 'Khu D', 'available', 0);

-- ==============================================================================
-- 7. ASSIGN ALL DEMO ACCOUNTS TO "Kho Thư viện FPT"
-- ==============================================================================
INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0001-000000000006'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'READ'
FROM users u WHERE u.email='customer@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='READ';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0002-000000000006'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'OPERATE'
FROM users u WHERE u.email='staff@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='OPERATE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0003-000000000006'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'MANAGE'
FROM users u WHERE u.email='manager@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0004-000000000006'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'MANAGE'
FROM users u WHERE u.email='business@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0005-000000000006'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111116'), 'MANAGE'
FROM users u WHERE u.email='admin@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

COMMIT;
SET FOREIGN_KEY_CHECKS = 1;
