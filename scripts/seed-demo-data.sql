USE storagehub_local;
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 1. Facility
INSERT INTO facilities (id, created_at, updated_at, code, name, address, city, status)
VALUES (
    UUID_TO_BIN('11111111-1111-1111-1111-111111111111'),
    NOW(6), NOW(6),
    'FAC-Q1',
    'StorageHub Chi nhánh Quận 1',
    '123 Nguyễn Huệ, Phường Bến Nghé, Quận 1',
    'Hồ Chí Minh',
    'active'
) ON DUPLICATE KEY UPDATE name=VALUES(name), status='active';

-- 2. Unit Type
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name,
    lengthm, widthm, heightm, monthly_price, max_load_kg,
    rack_count, rack_lengthm, rack_widthm, rack_heightm,
    status, version
) VALUES (
    UUID_TO_BIN('22222222-2222-2222-2222-222222222222'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    'UT-LARGE-01',
    'Kho Tiêu Chuẩn L (15m3)',
    5.00, 3.00, 3.00, 2500000.00, 1000.00,
    2, 2.00, 1.00, 2.00,
    'active', 1
) ON DUPLICATE KEY UPDATE name=VALUES(name), monthly_price=VALUES(monthly_price), status='active';

-- 3. Storage Units
INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES 
(
    UUID_TO_BIN('33333333-3333-3333-3333-333333333333'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    (SELECT id FROM unit_types WHERE code = 'UT-LARGE-01'),
    'Q1-U101', '1', 'A', 'occupied', 1
) ON DUPLICATE KEY UPDATE status='occupied';

INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES 
(
    UUID_TO_BIN('33333333-3333-3333-3333-333333333334'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    (SELECT id FROM unit_types WHERE code = 'UT-LARGE-01'),
    'Q1-U102', '1', 'A', 'available', 1
) ON DUPLICATE KEY UPDATE status='available';

-- 4. Users (Password: Password123@)
INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES
(
    UUID_TO_BIN('44444444-4444-4444-4444-444444444444'),
    NOW(6), NOW(6),
    'demo_manager@storagehub.test',
    'Nguyễn Quản Lý (Manager Q1)',
    '0901112222',
    '$2a$12$Qr0pGit4MR2CN3ETGjVJuuC/V3VmJXtCzt1WYgHSHiCxwQqIi7BQC',
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', password_hash=VALUES(password_hash), must_change_password=0;

INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES
(
    UUID_TO_BIN('55555555-5555-5555-5555-555555555555'),
    NOW(6), NOW(6),
    'demo_staff@storagehub.test',
    'Trần Nhân Viên (Staff Q1)',
    '0903334444',
    '$2a$12$Qr0pGit4MR2CN3ETGjVJuuC/V3VmJXtCzt1WYgHSHiCxwQqIi7BQC',
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', password_hash=VALUES(password_hash), must_change_password=0;

INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES
(
    UUID_TO_BIN('66666666-6666-6666-6666-666666666666'),
    NOW(6), NOW(6),
    'demo_customer@storagehub.test',
    'Lê Khách Hàng (Customer)',
    '0908889999',
    '$2a$12$Qr0pGit4MR2CN3ETGjVJuuC/V3VmJXtCzt1WYgHSHiCxwQqIi7BQC',
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', password_hash=VALUES(password_hash), must_change_password=0;

INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES
(
    UUID_TO_BIN('77777777-7777-7777-7777-777777777777'),
    NOW(6), NOW(6),
    'demo_business@storagehub.test',
    'Phạm Kinh Doanh (Business Ops)',
    '0905556666',
    '$2a$12$Qr0pGit4MR2CN3ETGjVJuuC/V3VmJXtCzt1WYgHSHiCxwQqIi7BQC',
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', password_hash=VALUES(password_hash), must_change_password=0;

-- 5. User Roles (using email mapping)
DELETE FROM user_roles WHERE user_id IN (
    SELECT id FROM users WHERE email IN (
        'demo_manager@storagehub.test',
        'demo_staff@storagehub.test',
        'demo_customer@storagehub.test',
        'demo_business@storagehub.test'
    )
);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'demo_manager@storagehub.test' AND r.code = 'MANAGER';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'demo_staff@storagehub.test' AND r.code = 'STAFF';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'demo_customer@storagehub.test' AND r.code = 'CUSTOMER';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'demo_business@storagehub.test' AND r.code = 'BUSINESS';

-- 6. User Facility Scopes (Manager MANAGE, Staff OPERATE on FAC-Q1)
DELETE FROM user_facility_scopes WHERE user_id IN (
    SELECT id FROM users WHERE email IN (
        'demo_manager@storagehub.test',
        'demo_staff@storagehub.test'
    )
);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT
    UUID_TO_BIN('44444444-4444-4444-4444-000000000001'),
    NOW(6), NOW(6),
    u.id,
    f.id,
    'MANAGE'
FROM users u, facilities f
WHERE u.email = 'demo_manager@storagehub.test' AND f.code = 'FAC-Q1';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT
    UUID_TO_BIN('55555555-5555-5555-5555-000000000001'),
    NOW(6), NOW(6),
    u.id,
    f.id,
    'OPERATE'
FROM users u, facilities f
WHERE u.email = 'demo_staff@storagehub.test' AND f.code = 'FAC-Q1';

-- 7. Reservation Quote
INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
) VALUES (
    UUID_TO_BIN('88888888-8888-8888-8888-888888888888'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'demo_customer@storagehub.test'),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    (SELECT id FROM unit_types WHERE code = 'UT-LARGE-01'),
    'PKG-6M', 'v1.0', '2026-01-01', '2026-06-30', 6,
    2500000.00, 15000000.00, 0.1000, 1500000.00, 13500000.00,
    2500000.00, 2500000.00, 11000000.00, 11000000.00, 16000000.00,
    NOW(6), DATE_ADD(NOW(6), INTERVAL 30 DAY)
) ON DUPLICATE KEY UPDATE total_after_discount=VALUES(total_after_discount);

-- 8. Reservation
INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
) VALUES (
    UUID_TO_BIN('99999999-9999-9999-9999-999999999999'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'demo_customer@storagehub.test'),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    (SELECT id FROM unit_types WHERE code = 'UT-LARGE-01'),
    (SELECT id FROM storage_units WHERE code = 'Q1-U101'),
    UUID_TO_BIN('88888888-8888-8888-8888-888888888888'),
    'RES-2026-0001', '2026-01-01', '2026-06-30',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 10.0, 500.0,
    13500000.00, 'IDEMP-RES-0001', 1
) ON DUPLICATE KEY UPDATE status='COMPLETED';

-- 9. Clean up previous demo return cases & maintenance tasks to ensure fresh demo runs
DELETE FROM return_cases WHERE rental_id = UUID_TO_BIN('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
DELETE FROM maintenance_tasks WHERE storage_unit_id = (SELECT id FROM storage_units WHERE code = 'Q1-U101');
DELETE FROM contracts WHERE rental_id = UUID_TO_BIN('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
DELETE FROM rentals WHERE id = UUID_TO_BIN('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');

INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
) VALUES (
    UUID_TO_BIN('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'demo_customer@storagehub.test'),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    (SELECT id FROM storage_units WHERE code = 'Q1-U101'),
    UUID_TO_BIN('99999999-9999-9999-9999-999999999999'),
    'active', '2026-01-01', '2026-06-30', 2500000.00
);

-- 10. Contract
INSERT INTO contracts (
    id, created_at, updated_at, rental_id, contract_number, status, signed_at, signed_by
) VALUES (
    UUID_TO_BIN('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'),
    NOW(6), NOW(6),
    UUID_TO_BIN('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
    'CTR-2026-0001', 'SIGNED', NOW(6), 'Lê Khách Hàng'
) ON DUPLICATE KEY UPDATE status='SIGNED';

-- 11. Payments
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version
) VALUES
(
    UUID_TO_BIN('cccccccc-cccc-cccc-cccc-cccccccccccc'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-9999-9999-9999-999999999999'),
    (SELECT id FROM users WHERE email = 'demo_customer@storagehub.test'),
    2500000.00, 'VND', 'PAY-DEP-0001', NOW(6), NOW(6),
    'RESERVATION_DEPOSIT', 'PAID', 1
) ON DUPLICATE KEY UPDATE status='PAID';

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version
) VALUES
(
    UUID_TO_BIN('cccccccc-cccc-cccc-cccc-cccccccccccd'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-9999-9999-9999-999999999999'),
    (SELECT id FROM users WHERE email = 'demo_customer@storagehub.test'),
    13500000.00, 'VND', 'PAY-RENT-0001', NOW(6), NOW(6),
    'MONTHLY_RENT', 'PAID', 1
) ON DUPLICATE KEY UPDATE status='PAID';

-- 12. Rental Package Policies
INSERT INTO rental_package_policies (
    id, created_at, updated_at, facility_id, code, name, rental_months,
    discount_rate, policy_version, effective_from, active
) VALUES
(
    UUID_TO_BIN('dddddddd-dddd-dddd-dddd-dddddddddddd'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    'PKG-3M', 'Gói thuê ngắn hạn 3 tháng', 3,
    0.0500, 'v1.0', '2026-01-01', 1
),
(
    UUID_TO_BIN('dddddddd-dddd-dddd-dddd-ddddddddddde'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'FAC-Q1'),
    'PKG-12M', 'Gói thuê dài hạn 12 tháng (Tiết kiệm)', 12,
    0.1500, 'v1.0', '2026-01-01', 1
) ON DUPLICATE KEY UPDATE discount_rate=VALUES(discount_rate);

-- 13. System Settings
INSERT INTO system_settings (
    id, created_at, updated_at, setting_key, setting_value, setting_type, group_name, label, description
) VALUES
(
    UUID_TO_BIN('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'),
    NOW(6), NOW(6),
    'settlement.auto_refund_max_amount', '5000000', 'NUMBER', 'FINANCE',
    'Ngưỡng hoàn tiền cọc tự động tối đa', 'Khoản cọc dưới giá trị này được hoàn tự động sau khi đối soát hợp lệ'
),
(
    UUID_TO_BIN('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeed'),
    NOW(6), NOW(6),
    'maintenance.sla_hours', '48', 'NUMBER', 'OPERATIONS',
    'Thời hạn SLA giải quyết bảo trì kho (giờ)', 'Thời gian tối đa để nhân viên hoàn thành xử lý sự cố kho'
) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value);
