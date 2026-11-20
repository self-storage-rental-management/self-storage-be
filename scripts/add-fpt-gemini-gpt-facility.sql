-- SQL Script to create Kho FPT Gemini GPT and ensure Staff / Manager have scopes across all facilities
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;
USE storagehub_local;
START TRANSACTION;

-- 1. Facility FPT Gemini GPT
INSERT INTO facilities (id, created_at, updated_at, code, name, address, city, image_url, climate_controlled, status)
VALUES
    (UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), NOW(6), NOW(6), 'FPT-GEMINI-GPT', 'Kho FPT Gemini GPT', 'Lô E2a-7, Đường D1, Khu Công nghệ cao, Phường Long Thạnh Mỹ, TP. Thủ Đức, TP. Hồ Chí Minh', 'TP. Hồ Chí Minh', '/images/customer-units/kho-s.jpg', 0, 'active')
ON DUPLICATE KEY UPDATE name=VALUES(name), address=VALUES(address), city=VALUES(city), image_url=VALUES(image_url), climate_controlled=VALUES(climate_controlled), status='active';

-- 2. Unit Types for FPT Gemini GPT
INSERT INTO unit_types (id, created_at, updated_at, facility_id, code, name, length_m, width_m, height_m, monthly_price, image_url, detail_image_url, max_load_kg, rack_count, rack_length_m, rack_width_m, rack_height_m, status, published_revision, accepted_goods_json, version)
VALUES
    (UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'S', 'Kho Nhỏ (S)', 8.00, 10.00, 5.00, 1000000.00, '/images/customer-units/kho-s.jpg', '/images/customer-units/details/kho-s-chi-tiet.png', 600.00, 4, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0),
    (UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'M', 'Kho Trung (M)', 12.60, 10.40, 5.00, 2000000.00, '/images/customer-units/kho-m.jpg', '/images/customer-units/details/kho-m-chi-tiet.png', 1200.00, 6, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0),
    (UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'L', 'Kho Lớn (L)', 18.30, 10.80, 5.00, 3000000.00, '/images/customer-units/kho-l.jpg', '/images/customer-units/details/kho-l-chi-tiet.png', 2400.00, 8, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0),
    (UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'XL', 'Kho Rất Lớn (XL)', 25.00, 11.20, 5.00, 4000000.00, '/images/customer-units/kho-xl.jpg', '/images/customer-units/details/kho-xl-chi-tiet.png', 3600.00, 10, 4.00, 2.00, 4.50, 'active', 0, '{"categoryIds":[],"materialIds":[]}', 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), length_m=VALUES(length_m), width_m=VALUES(width_m), height_m=VALUES(height_m), monthly_price=VALUES(monthly_price), image_url=VALUES(image_url), detail_image_url=VALUES(detail_image_url), max_load_kg=VALUES(max_load_kg), rack_count=VALUES(rack_count), status='active';

-- Allow demo unit types to use every active goods category and material from catalog
UPDATE unit_types
SET accepted_goods_json = JSON_OBJECT(
    'categoryIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_type_catalog WHERE status='ACTIVE'), JSON_ARRAY()),
    'materialIds', COALESCE((SELECT JSON_ARRAYAGG(BIN_TO_UUID(id)) FROM goods_material_catalog WHERE status='ACTIVE'), JSON_ARRAY())
)
WHERE facility_id = UUID_TO_BIN('11111111-1111-1111-1111-111111111114');

-- 3. Rental Packages for FPT Gemini GPT
INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, free_setup_days, discount_rate, deposit_rate, early_termination_fee_cap_months, early_termination_fee_coefficient, early_termination_notice_days, policy_version, publication_status, active, effective_from)
VALUES
    (UUID_TO_BIN('66666666-6666-6666-0013-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'DEMO_3M', 'Gói thuê 3 tháng', 3, 3, 0.0000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0014-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'DEMO_6M', 'Gói thuê 6 tháng', 6, 7, 0.0500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0015-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'DEMO_12M', 'Gói thuê 12 tháng', 12, 15, 0.1000, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01'),
    (UUID_TO_BIN('66666666-6666-6666-0016-000000000001'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), 'DEMO_24M', 'Gói thuê 24 tháng', 24, 30, 0.1500, 0.0500, 1, 1.0000, 14, 'demo-v1', 'PUBLISHED', 1, '2020-01-01')
ON DUPLICATE KEY UPDATE name=VALUES(name), free_setup_days=VALUES(free_setup_days), discount_rate=VALUES(discount_rate), deposit_rate=VALUES(deposit_rate), policy_version=VALUES(policy_version), publication_status=VALUES(publication_status), active=VALUES(active);

-- 4. Storage Units for FPT Gemini GPT (20 units: S, M, L, XL - unit 001 available, others inactive)
INSERT INTO storage_units (id, created_at, updated_at, facility_id, unit_type_id, code, floor, zone, status, version)
VALUES
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000061'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), 'FPT-GEMINI-GPT-S-001', '1', 'Khu A', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000062'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), 'FPT-GEMINI-GPT-S-002', '1', 'Khu A', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000063'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), 'FPT-GEMINI-GPT-S-003', '1', 'Khu A', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000064'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), 'FPT-GEMINI-GPT-S-004', '1', 'Khu A', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000065'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0013-000000000001'), 'FPT-GEMINI-GPT-S-005', '1', 'Khu A', 'inactive', 0),

    (UUID_TO_BIN('33333333-3333-3333-0000-000000000066'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), 'FPT-GEMINI-GPT-M-001', '2', 'Khu B', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000067'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), 'FPT-GEMINI-GPT-M-002', '2', 'Khu B', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000068'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), 'FPT-GEMINI-GPT-M-003', '2', 'Khu B', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000069'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), 'FPT-GEMINI-GPT-M-004', '2', 'Khu B', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000070'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0014-000000000001'), 'FPT-GEMINI-GPT-M-005', '2', 'Khu B', 'inactive', 0),

    (UUID_TO_BIN('33333333-3333-3333-0000-000000000071'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), 'FPT-GEMINI-GPT-L-001', '3', 'Khu C', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000072'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), 'FPT-GEMINI-GPT-L-002', '3', 'Khu C', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000073'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), 'FPT-GEMINI-GPT-L-003', '3', 'Khu C', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000074'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), 'FPT-GEMINI-GPT-L-004', '3', 'Khu C', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000075'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0015-000000000001'), 'FPT-GEMINI-GPT-L-005', '3', 'Khu C', 'inactive', 0),

    (UUID_TO_BIN('33333333-3333-3333-0000-000000000076'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), 'FPT-GEMINI-GPT-XL-001', '4', 'Khu D', 'available', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000077'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), 'FPT-GEMINI-GPT-XL-002', '4', 'Khu D', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000078'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), 'FPT-GEMINI-GPT-XL-003', '4', 'Khu D', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000079'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), 'FPT-GEMINI-GPT-XL-004', '4', 'Khu D', 'inactive', 0),
    (UUID_TO_BIN('33333333-3333-3333-0000-000000000080'), NOW(6), NOW(6), UUID_TO_BIN('11111111-1111-1111-1111-111111111114'), UUID_TO_BIN('22222222-2222-2222-0016-000000000001'), 'FPT-GEMINI-GPT-XL-005', '4', 'Khu D', 'inactive', 0)
ON DUPLICATE KEY UPDATE facility_id=VALUES(facility_id), unit_type_id=VALUES(unit_type_id), floor=VALUES(floor), zone=VALUES(zone), status=VALUES(status);

-- 5. Assign scopes for ALL facilities to staff@storagehub.demo, manager@storagehub.demo, customer@storagehub.demo
INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN(UUID()), NOW(6), NOW(6), u.id, f.id, 'OPERATE'
FROM users u CROSS JOIN facilities f
WHERE u.email = 'staff@storagehub.demo' AND f.status = 'active'
ON DUPLICATE KEY UPDATE scope_level='OPERATE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN(UUID()), NOW(6), NOW(6), u.id, f.id, 'MANAGE'
FROM users u CROSS JOIN facilities f
WHERE u.email = 'manager@storagehub.demo' AND f.status = 'active'
ON DUPLICATE KEY UPDATE scope_level='MANAGE';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT UUID_TO_BIN(UUID()), NOW(6), NOW(6), u.id, f.id, 'READ'
FROM users u CROSS JOIN facilities f
WHERE u.email = 'customer@storagehub.demo' AND f.status = 'active'
ON DUPLICATE KEY UPDATE scope_level='READ';

COMMIT;
