-- ==============================================================================
-- STORAGEHUB - KỊCH BẢN DỮ LIỆU MẪU TOÀN TRÌNH NGHIỆP VỤ (END-TO-END BUSINESS FLOW)
-- Luồng dữ liệu:
--   1. BO / Admin cấu hình cơ sở mới, loại kho, gian kho & chính sách giá
--   2. Tài khoản người dùng (BO, Manager, Staff, Customer) & phân quyền Scope
--   3. Khách hàng xem cơ sở -> Báo giá -> Tạo đơn đặt kho (Reservation)
--   4. Khách hàng thanh toán tiền cọc giữ chỗ & tiền thuê (Payments)
--   5. Gán gian kho (Unit Assignment) -> Ký hợp đồng (Contract)
--   6. Bàn giao nhận kho (Check-in) -> Hợp đồng thuê kích hoạt (Active Rental)
-- ==============================================================================

USE storagehub_local;
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- ------------------------------------------------------------------------------
-- PHẦN 1: BO / ADMIN THIẾT LẬP HẠ TẦNG (FACILITY, UNIT TYPES, STORAGE UNITS)
-- ------------------------------------------------------------------------------

-- 1.1 Tạo cơ sở lưu trữ mới: Chi nhánh Quận 7 (HCM-Q7-F01)
INSERT INTO facilities (id, created_at, updated_at, code, name, address, city, status)
VALUES (
    UUID_TO_BIN('11111111-2222-3333-4444-000000000007'),
    NOW(6), NOW(6),
    'HCM-Q7-F01',
    'Kho Việt – Cơ sở Quận 7 (Phú Mỹ Hưng)',
    '105 Nguyễn Lương Bằng, Phường Tân Phú, Quận 7, TP. Hồ Chí Minh',
    'TP. Hồ Chí Minh',
    'active'
) ON DUPLICATE KEY UPDATE name=VALUES(name), status='active', address=VALUES(address);

-- 1.2 Tạo 3 loại gian kho tiêu chuẩn của cơ sở Q7
-- Loại S: Kho Nhỏ Cá Nhân (8m³)
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name,
    lengthm, widthm, heightm, monthly_price, max_load_kg,
    rack_count, rack_lengthm, rack_widthm, rack_heightm,
    status, version
) VALUES (
    UUID_TO_BIN('22222222-7777-0001-0000-000000000001'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'Q7-UT-S',
    'Kho Mini Cá Nhân (S - 8m³)',
    2.00, 1.50, 2.70, 3500000.00, 500.00,
    2, 1.50, 0.50, 2.00,
    'active', 1
) ON DUPLICATE KEY UPDATE name=VALUES(name), monthly_price=VALUES(monthly_price), status='active';

-- Loại M: Kho Trung Gia Đình (16m³)
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name,
    lengthm, widthm, heightm, monthly_price, max_load_kg,
    rack_count, rack_lengthm, rack_widthm, rack_heightm,
    status, version
) VALUES (
    UUID_TO_BIN('22222222-7777-0002-0000-000000000002'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'Q7-UT-M',
    'Kho Tiêu Chuẩn Gia Đình (M - 16m³)',
    3.50, 2.00, 2.70, 7000000.00, 1200.00,
    4, 2.00, 0.60, 2.20,
    'active', 1
) ON DUPLICATE KEY UPDATE name=VALUES(name), monthly_price=VALUES(monthly_price), status='active';

-- Loại L: Kho Lớn Doanh Nghiệp (30m³)
INSERT INTO unit_types (
    id, created_at, updated_at, facility_id, code, name,
    lengthm, widthm, heightm, monthly_price, max_load_kg,
    rack_count, rack_lengthm, rack_widthm, rack_heightm,
    status, version
) VALUES (
    UUID_TO_BIN('22222222-7777-0003-0000-000000000003'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'Q7-UT-L',
    'Kho Lớn Doanh Nghiệp / E-Commerce (L - 30m³)',
    5.00, 2.50, 2.70, 14000000.00, 2500.00,
    6, 2.50, 0.80, 2.40,
    'active', 1
) ON DUPLICATE KEY UPDATE name=VALUES(name), monthly_price=VALUES(monthly_price), status='active';

-- 1.3 Tạo các gian kho cụ thể thuộc cơ sở Q7
-- Gian Q7-U101: Loại M, đang được thuê (occupied) theo hợp đồng mẫu
INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES (
    UUID_TO_BIN('33333333-7777-0101-0000-000000000101'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-M'),
    'Q7-U101', '1', 'Khu A', 'occupied', 1
) ON DUPLICATE KEY UPDATE status='occupied';

-- Gian Q7-U102: Loại M, trạng thái Available (sẵn sàng cho khách mới đặt)
INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES (
    UUID_TO_BIN('33333333-7777-0102-0000-000000000102'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-M'),
    'Q7-U102', '1', 'Khu A', 'available', 1
) ON DUPLICATE KEY UPDATE status='available';

-- Gian Q7-U201: Loại L, trạng thái Available
INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES (
    UUID_TO_BIN('33333333-7777-0201-0000-000000000201'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-L'),
    'Q7-U201', '2', 'Khu B', 'available', 1
) ON DUPLICATE KEY UPDATE status='available';

-- Gian Q7-U103: Loại S, trạng thái Maintenance (đang bảo trì)
INSERT INTO storage_units (
    id, created_at, updated_at, facility_id, unit_type_id,
    code, floor, zone, status, version
) VALUES (
    UUID_TO_BIN('33333333-7777-0103-0000-000000000103'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-S'),
    'Q7-U103', '1', 'Khu C', 'maintenance', 1
) ON DUPLICATE KEY UPDATE status='maintenance';

-- 1.4 BO cấu hình các gói chính sách giá (Rental Package Policies) cho cơ sở Q7
INSERT INTO rental_package_policies (
    id, created_at, updated_at, facility_id, code, name, rental_months,
    discount_rate, policy_version, effective_from, active
) VALUES
(
    UUID_TO_BIN('dddddddd-7777-0001-0000-000000000001'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'PKG-Q7-1M', 'Gói thuê ngắn hạn 1 tháng', 1,
    0.0000, 'v1.0', '2026-01-01', 1
),
(
    UUID_TO_BIN('dddddddd-7777-0003-0000-000000000003'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'PKG-Q7-3M', 'Gói thuê trải nghiệm 3 tháng', 3,
    0.0500, 'v1.0', '2026-01-01', 1
),
(
    UUID_TO_BIN('dddddddd-7777-0006-0000-000000000006'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'PKG-Q7-6M', 'Gói thuê tiêu chuẩn 6 tháng (Giảm 10%)', 6,
    0.1000, 'v1.0', '2026-01-01', 1
),
(
    UUID_TO_BIN('dddddddd-7777-0012-0000-000000000012'),
    NOW(6), NOW(6),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    'PKG-Q7-12M', 'Gói thuê dài hạn 12 tháng (Tiết kiệm 15%)', 12,
    0.1500, 'v1.0', '2026-01-01', 1
) ON DUPLICATE KEY UPDATE discount_rate=VALUES(discount_rate), active=1;


-- ------------------------------------------------------------------------------
-- PHẦN 2: TÀI KHOẢN NGƯỜI DÙNG & PHÂN QUYỀN THEO CHUẨN LEADER (@storagehub.demo)
-- ------------------------------------------------------------------------------

-- Quản trị viên hệ thống (Admin - Password: Admin@1234!)
INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES (
    UUID_TO_BIN('00000000-0000-0000-0000-000000000001'),
    NOW(6), NOW(6),
    'admin@storagehub.demo',
    'StorageHub Administrator (Hệ Thống)',
    '+84 901 000 999',
    '$2a$12$LhSiU5K9dOwj5pxgwZVnsOJA9M3Vqm0tIL8cK1gP.B0Duf1lTD2OO', -- Admin@1234!
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', password_hash=VALUES(password_hash), must_change_password=0;

-- Quản lý cơ sở Q7 (Manager Q7 - Password: Manager@1234!)
INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES (
    UUID_TO_BIN('44444444-7777-4444-0000-000000000001'),
    NOW(6), NOW(6),
    'manager.q7@storagehub.demo',
    'Hoàng Quản Lý Q7',
    '0907111222',
    '$2a$10$wO3/gMeqd086xL3uYwJg4.43q4O90g3nN.Zq0hG18yqE9JdF2WwIu', -- Manager@1234!
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE';

-- Nhân viên vận hành Q7 (Staff Q7 - Password: Staff@1234!)
INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES (
    UUID_TO_BIN('55555555-7777-5555-0000-000000000001'),
    NOW(6), NOW(6),
    'staff.q7@storagehub.demo',
    'Vũ Nhân Viên Q7',
    '0907333444',
    '$2a$10$X8B0N1c5eN0w1W5K6m.ZueoZ.zY1o7G8w9qF0r2yqE9JdF2WwIu', -- Staff@1234!
    'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE';

-- Gán quyền Role
DELETE FROM user_roles WHERE user_id IN (
    SELECT id FROM users WHERE email IN ('admin@storagehub.demo', 'manager.q7@storagehub.demo', 'staff.q7@storagehub.demo')
);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'admin@storagehub.demo' AND r.code = 'ADMIN';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'manager.q7@storagehub.demo' AND r.code = 'MANAGER';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r 
WHERE u.email = 'staff.q7@storagehub.demo' AND r.code = 'STAFF';

-- Gán phạm vi cơ sở (User Facility Scope): Manager MANAGE, Staff OPERATE trên cơ sở Q7
DELETE FROM user_facility_scopes WHERE user_id IN (
    SELECT id FROM users WHERE email IN ('manager.q7@storagehub.demo', 'staff.q7@storagehub.demo')
);

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT
    UUID_TO_BIN('44444444-7777-4444-0000-000000000002'),
    NOW(6), NOW(6),
    u.id, f.id, 'MANAGE'
FROM users u, facilities f
WHERE u.email = 'manager.q7@storagehub.demo' AND f.code = 'HCM-Q7-F01';

INSERT INTO user_facility_scopes (id, created_at, updated_at, user_id, facility_id, scope_level)
SELECT
    UUID_TO_BIN('55555555-7777-5555-0000-000000000002'),
    NOW(6), NOW(6),
    u.id, f.id, 'OPERATE'
FROM users u, facilities f
WHERE u.email = 'staff.q7@storagehub.demo' AND f.code = 'HCM-Q7-F01';


-- ------------------------------------------------------------------------------
-- PHẦN 3: KHÁCH HÀNG CHỌN KHO, TẠO BÁO GIÁ & ĐẶT KHO (RESERVATION WORKFLOW)
-- ------------------------------------------------------------------------------

-- Dọn dẹp các bản ghi phụ thuộc cũ nếu đã từng chạy
DELETE FROM check_ins WHERE reservation_id = UUID_TO_BIN('99999999-7777-9999-0000-000000000001');
DELETE FROM contracts WHERE contract_number = 'CTR-Q7-2026-0001';
DELETE FROM rentals WHERE id = UUID_TO_BIN('aaaaaaaa-7777-aaaa-0000-000000000001');
DELETE FROM unit_assignments WHERE reservation_id = UUID_TO_BIN('99999999-7777-9999-0000-000000000001');
DELETE FROM payments WHERE idempotency_key IN ('PAY-Q7-DEP-0001', 'PAY-Q7-RENT-0001');
DELETE FROM reservation_pricing_snapshots WHERE reservation_id = UUID_TO_BIN('99999999-7777-9999-0000-000000000001');
DELETE FROM reservations WHERE reservation_code = 'RES-Q7-2026-0001';
DELETE FROM reservation_quotes WHERE id = UUID_TO_BIN('88888888-7777-8888-0000-000000000001');

-- 3.1 Khách hàng nhận báo giá (Reservation Quote):
-- Thuê loại M (7.000.000đ/tháng) trong 6 tháng (Gói PKG-Q7-6M giảm 10%)
--   - Tiền thuê gốc: 42.000.000đ
--   - Giảm giá (10%): -4.200.000đ
--   - Tiền thuê thực thu (Total after discount): 37.800.000đ
--   - Tiền cọc giữ chỗ (1 tháng): 7.000.000đ
--   - Tiền cọc an ninh hoàn lại (1 tháng): 7.000.000đ
--   - Còn lại thanh toán khi check-in: 37.800.000đ
INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
) VALUES (
    UUID_TO_BIN('88888888-7777-8888-0000-000000000001'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'customer@storagehub.demo'),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-M'),
    'PKG-Q7-6M', 'v1.0', '2026-10-01', '2027-03-31', 6,
    7000000.00, 42000000.00, 0.1000, 4200000.00, 37800000.00,
    7000000.00, 7000000.00, 30800000.00, 37800000.00, 44800000.00,
    NOW(6), DATE_ADD(NOW(6), INTERVAL 30 DAY)
);

-- 3.2 Khách hàng chốt đặt kho (Reservation):
-- Mã đơn: RES-Q7-2026-0001, đã phân công gian kho Q7-U101
INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
) VALUES (
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'customer@storagehub.demo'),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM unit_types WHERE code = 'Q7-UT-M'),
    (SELECT id FROM storage_units WHERE code = 'Q7-U101'),
    UUID_TO_BIN('88888888-7777-8888-0000-000000000001'),
    'RES-Q7-2026-0001', '2026-10-01', '2027-03-31',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 12.5, 600.0,
    37800000.00, 'IDEMP-RES-Q7-0001', 1
);

-- 3.3 Lưu vết Snapshot giá bất biến (Bảo vệ tính toàn vẹn hóa đơn khi chính sách sửa/xóa)
INSERT INTO reservation_pricing_snapshots (
    id, created_at, updated_at, reservation_id, pricing_package_code, pricing_policy_version,
    rental_months, monthly_price, gross_rental_amount, discount_rate, discount_amount,
    net_rental_amount, reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
) VALUES (
    UUID_TO_BIN('99999999-7777-9999-0000-000000000002'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    'PKG-Q7-6M', 'v1.0', 6,
    7000000.00, 42000000.00, 0.1000, 4200000.00,
    37800000.00, 7000000.00, 7000000.00, 30800000.00,
    37800000.00, 44800000.00, NOW(6), DATE_ADD(NOW(6), INTERVAL 30 DAY)
);


-- ------------------------------------------------------------------------------
-- PHẦN 4: THANH TOÁN (PAYMENTS - CỌC GIỮ CHỖ & TIỀN THUÊ)
-- ------------------------------------------------------------------------------

-- 4.1 Khách thanh toán cọc giữ chỗ (Reservation Deposit: 7.000.000đ)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version
) VALUES (
    UUID_TO_BIN('cccccccc-7777-cccc-0000-000000000001'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    (SELECT id FROM users WHERE email = 'customer@storagehub.demo'),
    7000000.00, 'VND', 'PAY-Q7-DEP-0001', NOW(6), NOW(6),
    'RESERVATION_DEPOSIT', 'PAID', 1
);

-- 4.2 Khách thanh toán tiền thuê còn lại (Net Rental Balance: 37.800.000đ)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version
) VALUES (
    UUID_TO_BIN('cccccccc-7777-cccc-0000-000000000002'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    (SELECT id FROM users WHERE email = 'customer@storagehub.demo'),
    37800000.00, 'VND', 'PAY-Q7-RENT-0001', NOW(6), NOW(6),
    'RENTAL_BALANCE', 'PAID', 1
);


-- ------------------------------------------------------------------------------
-- PHẦN 5: GÁN GIAN KHO, HỢP ĐỒNG THUÊ & NHẬN KHO (ACTIVE RENTAL LIFECYCLE)
-- ------------------------------------------------------------------------------

-- 5.1 Nhân viên xác nhận gán gian kho Q7-U101 cho khách
INSERT INTO unit_assignments (
    id, created_at, updated_at, reservation_id, storage_unit_id,
    assigned_by, assigned_at, status, version
) VALUES (
    UUID_TO_BIN('77777777-7777-0001-0000-000000000001'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    (SELECT id FROM storage_units WHERE code = 'Q7-U101'),
    (SELECT id FROM users WHERE email = 'staff.q7@storagehub.demo'),
    NOW(6), 'COMPLETED', 1
);

-- 5.2 Khởi tạo Hợp đồng thuê đang kích hoạt (Active Rental)
INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
) VALUES (
    UUID_TO_BIN('aaaaaaaa-7777-aaaa-0000-000000000001'),
    NOW(6), NOW(6),
    (SELECT id FROM users WHERE email = 'customer@storagehub.demo'),
    (SELECT id FROM facilities WHERE code = 'HCM-Q7-F01'),
    (SELECT id FROM storage_units WHERE code = 'Q7-U101'),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    'active', '2026-10-01', '2027-03-31', 7000000.00
);

-- 5.3 Hợp đồng pháp lý đã ký điện tử (Signed Contract)
INSERT INTO contracts (
    id, created_at, updated_at, rental_id, contract_number, status, signed_at, signed_by
) VALUES (
    UUID_TO_BIN('bbbbbbbb-7777-bbbb-0000-000000000001'),
    NOW(6), NOW(6),
    UUID_TO_BIN('aaaaaaaa-7777-aaaa-0000-000000000001'),
    'CTR-Q7-2026-0001', 'SIGNED', NOW(6), 'Demo Customer'
);

-- 5.4 Biên bản nhận kho (Check-in handover hoàn tất, đã bàn giao thẻ và chìa)
INSERT INTO check_ins (
    id, created_at, updated_at, reservation_id, rental_id, performed_by,
    scheduled_at, checked_in_at, status, checklist_json, version
) VALUES (
    UUID_TO_BIN('66666666-7777-6666-0000-000000000001'),
    NOW(6), NOW(6),
    UUID_TO_BIN('99999999-7777-9999-0000-000000000001'),
    UUID_TO_BIN('aaaaaaaa-7777-aaaa-0000-000000000001'),
    (SELECT id FROM users WHERE email = 'staff.q7@storagehub.demo'),
    NOW(6), NOW(6), 'completed',
    '{"keyHandedOver": true, "rfidCardHandedOver": true, "padlockHandedOver": true, "customerSignature": true, "notes": "Khách hàng đã nhận bàn giao kho Q7-U101 trong tình trạng sạch sẽ, đầy đủ thiết bị"}',
    1
);
