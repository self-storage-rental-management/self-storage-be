-- SQL Script to create Kho Bình Nước with 2 units of size S at price 1,200,000 VND
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
USE storagehub_local;
START TRANSACTION;

-- 1. Facility: Kho Bình Nước
INSERT INTO facilities (
    id, created_at, updated_at, code, name, address, city, phone, access_hours, image_url, climate_controlled, security_description, status
) VALUES (
    UUID_TO_BIN('11111111-1111-1111-1111-111111111118'),
    NOW(6),
    NOW(6),
    'HCM-BN-F01',
    'Kho Bình Nước',
    'Số 123 Đường Bình Nước, Phường Linh Trung, TP. Thủ Đức, TP. Hồ Chí Minh',
    'TP. Hồ Chí Minh',
    '+84 28 3888 9999',
    '24/7',
    '/images/customer-units/kho-m.jpg',
    1,
    'Bảo vệ 24/7, camera giám sát CCTV toàn khu, thẻ từ kiểm soát ra vào và hệ thống PCCC tiêu chuẩn quốc tế',
    'active'
) ON DUPLICATE KEY UPDATE
    name=VALUES(name),
    address=VALUES(address),
    city=VALUES(city),
    phone=VALUES(phone),
    access_hours=VALUES(access_hours),
    image_url=VALUES(image_url),
    climate_controlled=VALUES(climate_controlled),
    security_description=VALUES(security_description),
    status='active';

-- 2. Unit Type S for Kho Bình Nước (Giá: 1.200.000 VND/tháng)
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name, length_m, width_m, height_m,
    monthly_price, security_deposit_amount, image_url, detail_image_url, max_load_kg, rack_count,
    rack_length_m, rack_width_m, rack_height_m, status, published_revision, accepted_goods_json, version
) VALUES (
    UUID_TO_BIN('22222222-2222-2222-0031-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'S', 'Kho Nhỏ (S)',
    12.00, 11.60, 5.00,
    1200000.00, 1200000.00,
    '/images/customer-units/kho-s.jpg', '/images/customer-units/details/kho-s-chi-tiet.png',
    600.00, 4, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
) ON DUPLICATE KEY UPDATE
    name=VALUES(name), length_m=VALUES(length_m), width_m=VALUES(width_m), height_m=VALUES(height_m),
    monthly_price=VALUES(monthly_price), security_deposit_amount=VALUES(security_deposit_amount),
    image_url=VALUES(image_url), detail_image_url=VALUES(detail_image_url),
    max_load_kg=VALUES(max_load_kg), rack_count=VALUES(rack_count), status='active';

-- Allow unit types to accept active goods categories and materials
UPDATE unit_types
SET accepted_goods_json = JSON_OBJECT(
    'categoryIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_type_catalog WHERE status='ACTIVE'), JSON_ARRAY()),
    'materialIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_material_catalog WHERE status='ACTIVE'), JSON_ARRAY())
)
WHERE facility_id = UUID_TO_BIN('11111111-1111-1111-1111-111111111118');

-- 3. Rental Packages for Kho Bình Nước (1M, 3M, 6M, 12M, 24M)
INSERT INTO rental_package_policies (
    id, created_at, updated_at, facility_id, code, name, rental_months, free_setup_days,
    discount_rate, deposit_rate, early_termination_fee_cap_months, early_termination_fee_coefficient,
    early_termination_notice_days, policy_version, publication_status, active, effective_from
) VALUES
(UUID_TO_BIN('66666666-6666-6666-0031-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'BN_1M', 'Gói thuê 1 tháng', 1, 0, 0.0000, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0032-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'BN_3M', 'Gói thuê 3 tháng', 3, 3, 0.0000, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0033-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'BN_6M', 'Gói thuê 6 tháng', 6, 7, 0.0500, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0034-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'BN_12M', 'Gói thuê 12 tháng', 12, 15, 0.1000, 0.2000, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01')
ON DUPLICATE KEY UPDATE
    name=VALUES(name), free_setup_days=VALUES(free_setup_days), discount_rate=VALUES(discount_rate),
    deposit_rate=VALUES(deposit_rate), policy_version=VALUES(policy_version), publication_status=VALUES(publication_status),
    active=VALUES(active);

-- 4. Storage Units for Kho Bình Nước (Đúng 2 kho S)
INSERT INTO storage_units (id, created_at, updated_at, facility_id, unit_type_id, code, floor, zone, status, version)
VALUES
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000201'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), UUID_TO_BIN('22222222-2222-2222-0031-000000000001'), 'HCM-BN-F01-S-001', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000202'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), UUID_TO_BIN('22222222-2222-2222-0031-000000000001'), 'HCM-BN-F01-S-002', '1', 'Khu A', 'available', 0)
ON DUPLICATE KEY UPDATE
    facility_id=VALUES(facility_id), unit_type_id=VALUES(unit_type_id), floor=VALUES(floor), zone=VALUES(zone), status=VALUES(status);

-- 5. Assign Scope MANAGE for manager@storagehub.demo
INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0003-000000000008'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'MANAGE'
FROM users u WHERE u.email='manager@storagehub.demo'
ON DUPLICATE KEY UPDATE facility_id=VALUES(facility_id), scope_level='MANAGE';

-- 6. Assign Scopes to other roles (Business, Admin, Staff, Customer)
INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0001-000000000008'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'READ'
FROM users u WHERE u.email='customer@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='READ';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0002-000000000008'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'OPERATE'
FROM users u WHERE u.email='staff@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='OPERATE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0004-000000000008'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'MANAGE'
FROM users u WHERE u.email='business@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0005-000000000008'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111118'), 'MANAGE'
FROM users u WHERE u.email='admin@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

COMMIT;
