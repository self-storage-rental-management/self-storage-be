-- SQL Script to create Kho Thư viện RMIT and equip it with units, types, packages, and scopes
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
USE storagehub_local;
START TRANSACTION;

-- 1. Facility: Kho Thư viện RMIT
INSERT INTO facilities (
    id, created_at, updated_at, code, name, address, city, phone, access_hours, image_url, climate_controlled, security_description, status
) VALUES (
    UUID_TO_BIN('11111111-1111-1111-1111-111111111117'),
    NOW(6),
    NOW(6),
    'RMIT-TV-F01',
    'Kho Thư viện RMIT',
    'Khuôn viên Thư viện RMIT, 702 Nguyễn Văn Linh, Phường Tân Phong, Quận 7, TP. Hồ Chí Minh',
    'TP. Hồ Chí Minh',
    '+84 28 3776 1300',
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

-- 2. Unit Types for Kho Thư viện RMIT (S, M, L, XL)
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name, length_m, width_m, height_m,
    monthly_price, image_url, detail_image_url, max_load_kg, rack_count,
    rack_length_m, rack_width_m, rack_height_m, status, published_revision, accepted_goods_json, version
) VALUES
(
    UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'S', 'Kho Nhỏ (S)',
    8.00, 10.00, 5.00, 1000000.00,
    '/images/customer-units/kho-s.jpg', '/images/customer-units/details/kho-s-chi-tiet.png',
    600.00, 4, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'M', 'Kho Trung (M)',
    12.60, 10.40, 5.00, 2000000.00,
    '/images/customer-units/kho-m.jpg', '/images/customer-units/details/kho-m-chi-tiet.png',
    1200.00, 6, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'L', 'Kho Lớn (L)',
    18.30, 10.80, 5.00, 3000000.00,
    '/images/customer-units/kho-l.jpg', '/images/customer-units/details/kho-l-chi-tiet.png',
    2400.00, 8, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
),
(
    UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), NOW(6), NOW(6),
    UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'XL', 'Kho Rất Lớn (XL)',
    25.00, 11.20, 5.00, 4000000.00,
    '/images/customer-units/kho-xl.jpg', '/images/customer-units/details/kho-xl-chi-tiet.png',
    3600.00, 10, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0
)
ON DUPLICATE KEY UPDATE
    name=VALUES(name), length_m=VALUES(length_m), width_m=VALUES(width_m), height_m=VALUES(height_m),
    monthly_price=VALUES(monthly_price), image_url=VALUES(image_url), detail_image_url=VALUES(detail_image_url),
    max_load_kg=VALUES(max_load_kg), rack_count=VALUES(rack_count), status='active';

-- Allow unit types to accept active goods categories and materials
UPDATE unit_types
SET accepted_goods_json = JSON_OBJECT(
    'categoryIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_type_catalog WHERE status='ACTIVE'), JSON_ARRAY()),
    'materialIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_material_catalog WHERE status='ACTIVE'), JSON_ARRAY())
)
WHERE facility_id = UUID_TO_BIN('11111111-1111-1111-1111-111111111117');

-- 3. Rental Packages for Kho Thư viện RMIT
INSERT INTO rental_package_policies (
    id, created_at, updated_at, facility_id, code, name, rental_months, free_setup_days,
    discount_rate, deposit_rate, early_termination_fee_cap_months, early_termination_fee_coefficient,
    early_termination_notice_days, policy_version, publication_status, active, effective_from
) VALUES
(UUID_TO_BIN('66666666-6666-6666-0021-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'DEMO_3M', 'Gói thuê 1', 3, 3, 0.0000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0022-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'DEMO_6M', 'Gói thuê 2', 6, 7, 0.0500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0023-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'DEMO_12M', 'Gói thuê 3', 12, 15, 0.1000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
(UUID_TO_BIN('66666666-6666-6666-0024-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'DEMO_24M', 'Gói thuê 4', 24, 30, 0.1500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01')
ON DUPLICATE KEY UPDATE
    name=VALUES(name), free_setup_days=VALUES(free_setup_days), discount_rate=VALUES(discount_rate),
    deposit_rate=VALUES(deposit_rate), policy_version=VALUES(policy_version), publication_status=VALUES(publication_status),
    active=VALUES(active);

-- 4. Storage Units for Kho Thư viện RMIT (20 units: S, M, L, XL - all available)
INSERT INTO storage_units (id, created_at, updated_at, facility_id, unit_type_id, code, floor, zone, status, version)
VALUES
    -- 5 Kho Nhỏ (S)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000101'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), 'RMIT-TV-F01-S-001', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000102'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), 'RMIT-TV-F01-S-002', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000103'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), 'RMIT-TV-F01-S-003', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000104'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), 'RMIT-TV-F01-S-004', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000105'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0021-000000000001'), 'RMIT-TV-F01-S-005', '1', 'Khu A', 'available', 0),

    -- 5 Kho Trung (M)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000106'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), 'RMIT-TV-F01-M-001', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000107'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), 'RMIT-TV-F01-M-002', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000108'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), 'RMIT-TV-F01-M-003', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000109'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), 'RMIT-TV-F01-M-004', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000110'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0022-000000000001'), 'RMIT-TV-F01-M-005', '2', 'Khu B', 'available', 0),

    -- 5 Kho Lớn (L)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000111'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), 'RMIT-TV-F01-L-001', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000112'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), 'RMIT-TV-F01-L-002', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000113'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), 'RMIT-TV-F01-L-003', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000114'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), 'RMIT-TV-F01-L-004', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000115'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0023-000000000001'), 'RMIT-TV-F01-L-005', '3', 'Khu C', 'available', 0),

    -- 5 Kho Rất Lớn (XL)
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000116'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), 'RMIT-TV-F01-XL-001', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000117'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), 'RMIT-TV-F01-XL-002', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000118'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), 'RMIT-TV-F01-XL-003', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000119'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), 'RMIT-TV-F01-XL-004', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000120'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), UUID_TO_BIN('22222222-2222-2222-0024-000000000001'), 'RMIT-TV-F01-XL-005', '4', 'Khu D', 'available', 0)
ON DUPLICATE KEY UPDATE
    facility_id=VALUES(facility_id), unit_type_id=VALUES(unit_type_id), floor=VALUES(floor), zone=VALUES(zone), status=VALUES(status);

-- 5. Assign Scopes for Kho Thư viện RMIT to Demo Accounts
INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0001-000000000007'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'READ'
FROM users u WHERE u.email='customer@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='READ';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0002-000000000007'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'OPERATE'
FROM users u WHERE u.email='staff@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='OPERATE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0003-000000000007'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'MANAGE'
FROM users u WHERE u.email='manager@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0004-000000000007'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'MANAGE'
FROM users u WHERE u.email='business@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN('55555555-5555-5555-0005-000000000007'), NOW(6), NOW(6), u.id, UUID_TO_BIN('11111111-1111-1111-1111-111111111117'), 'MANAGE'
FROM users u WHERE u.email='admin@storagehub.demo'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

COMMIT;
