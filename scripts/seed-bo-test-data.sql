-- ==============================================================================
-- STORAGEHUB - BỘ DỮ LIỆU TEST CHUYÊN BIỆT CHO PHÂN HỆ BAN ĐIỀU HÀNH (BO / BUSINESS OPERATIONS)
-- ==============================================================================

USE storagehub_local;
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

START TRANSACTION;

-- ------------------------------------------------------------------------------
-- 1. CẤU HÌNH VẬN HÀNH TOÀN HỆ THỐNG (SYSTEM SETTINGS - BO CONFIGURATION)
-- ------------------------------------------------------------------------------
INSERT INTO system_settings (id, created_at, updated_at, group_name, setting_key, setting_value, setting_type, label, description)
VALUES
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000001'), NOW(6), NOW(6), 'OPERATIONS', 'gracePeriod', '5', 'INTEGER', 'Số ngày ân hạn thanh toán', 'Thời gian gia hạn trước khi tính phí chậm trả'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000002'), NOW(6), NOW(6), 'OPERATIONS', 'lateFeeAmount', '700000.00', 'DECIMAL', 'Mức phí phạt chậm trả', 'Phí phát sinh khi quá hạn hợp đồng thuê hoặc đợt gia hạn'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000003'), NOW(6), NOW(6), 'FINANCIAL', 'defaultDepositRatio', '0.20', 'DECIMAL', 'Tỷ lệ cọc giữ chỗ mặc định', 'Tỷ lệ cọc giữ chỗ tính trên giá trị hợp đồng thuê'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000004'), NOW(6), NOW(6), 'OPERATIONS', 'holdExpiryHours', '2.0', 'DECIMAL', 'Thời hạn giữ chỗ tạm thời', 'Số giờ giữ gian kho tạm thời trước khi giải phóng'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000005'), NOW(6), NOW(6), 'CARGO', 'dimDivisor', '5000', 'INTEGER', 'Hệ số thể tích quy đổi (DIM)', 'Hệ số chia thể tích ra khối lượng tương đương'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000006'), NOW(6), NOW(6), 'SYSTEM', 'maintenanceMode', 'false', 'BOOLEAN', 'Chế độ bảo trì hệ thống', 'Bật tắt chế độ tạm ngưng nhận yêu cầu mới'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000007'), NOW(6), NOW(6), 'NOTICES', 'bannerNotice', 'Chào mừng quý khách đến với StorageHub - Hệ thống kho tự quản thông minh hàng đầu', 'STRING', 'Thông báo biểu ngữ hệ thống', 'Thông báo hiển thị trên cổng khách hàng và back-office'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000008'), NOW(6), NOW(6), 'BILLING', 'autoInvoiceDays', '7', 'INTEGER', 'Số ngày xuất hóa đơn trước hạn', 'Thời gian thông báo hóa đơn gia hạn tự động'),
    (UUID_TO_BIN('b0b00001-0000-0000-0000-000000000009'), NOW(6), NOW(6), 'BILLING', 'autoProrate', 'true', 'BOOLEAN', 'Tự động tính tiền lẻ ngày', 'Cho phép tính tiền tỷ lệ theo ngày bắt đầu thực tế')
ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value), updated_at=NOW(6);

-- ------------------------------------------------------------------------------
-- 2. TÀI KHOẢN NGƯỜI DÙNG & VAI TRÒ BO (BUSINESS OPERATIONS)
-- ------------------------------------------------------------------------------
INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES (
    UUID_TO_BIN('44444444-4444-4444-0004-000000000001'), NOW(6), NOW(6),
    'business@storagehub.demo', 'Phạm Trưởng Ban Điều Hành (BO)', '+84 28 3999 1111',
    '$2a$12$VIzVqC24HiSToqyjtkf09.EOrkrQ3NsCPm0MFO0YRoekI4tatA8Pa', 'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), password_hash=VALUES(password_hash), status='ACTIVE', must_change_password=0;

INSERT INTO users (id, created_at, updated_at, email, full_name, phone, password_hash, status, must_change_password)
VALUES (
    UUID_TO_BIN('77777777-7777-7777-7777-777777777777'), NOW(6), NOW(6),
    'demo_business@storagehub.test', 'Phạm Kinh Doanh (Business Ops)', '+84 908 999 000',
    '$2a$10$7zBgn9/X5u9u3b0R2rX01ee3n6Y0hC4Z4pA2f0x8mD6f4h2e1v9u2', 'ACTIVE', 0
) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), status='ACTIVE', must_change_password=0;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email IN ('business@storagehub.demo', 'demo_business@storagehub.test') AND r.code='BUSINESS'
ON DUPLICATE KEY UPDATE role_id=VALUES(role_id);

-- ------------------------------------------------------------------------------
-- 3. CẬP NHẬT TRẠNG THÁI GIAN KHO TẠO TỶ LỆ LẤP ĐẦY THỰC TẾ
-- ------------------------------------------------------------------------------
-- Q1: 20 gian kho -> 12 occupied (60%), 1 maintenance, 1 reserved, 6 available
UPDATE storage_units SET status = 'occupied'
WHERE code IN ('HCM-Q1-F01-S-001', 'HCM-Q1-F01-S-002', 'HCM-Q1-F01-S-003',
               'HCM-Q1-F01-M-001', 'HCM-Q1-F01-M-002', 'HCM-Q1-F01-M-003',
               'HCM-Q1-F01-L-001', 'HCM-Q1-F01-L-002', 'HCM-Q1-F01-L-003',
               'HCM-Q1-F01-XL-001', 'HCM-Q1-F01-XL-002', 'HCM-Q1-F01-XL-003');

UPDATE storage_units SET status = 'maintenance' WHERE code = 'HCM-Q1-F01-L-004';
UPDATE storage_units SET status = 'reserved'    WHERE code = 'HCM-Q1-F01-M-004';
UPDATE storage_units SET status = 'available'   WHERE code IN ('HCM-Q1-F01-S-004', 'HCM-Q1-F01-S-005', 'HCM-Q1-F01-M-005', 'HCM-Q1-F01-L-005', 'HCM-Q1-F01-XL-004', 'HCM-Q1-F01-XL-005');

-- Bình Dương: 20 gian kho -> 10 occupied (50%), 2 maintenance, 1 reserved, 7 available
UPDATE storage_units SET status = 'occupied'
WHERE code IN ('BD-F01-S-001', 'BD-F01-S-002', 'BD-F01-S-003',
               'BD-F01-M-001', 'BD-F01-M-002', 'BD-F01-M-003',
               'BD-F01-L-001', 'BD-F01-L-002',
               'BD-F01-XL-001', 'BD-F01-XL-002');

UPDATE storage_units SET status = 'maintenance' WHERE code IN ('BD-F01-L-003', 'BD-F01-S-004');
UPDATE storage_units SET status = 'reserved'    WHERE code = 'BD-F01-XL-003';
UPDATE storage_units SET status = 'available'   WHERE code IN ('BD-F01-S-005', 'BD-F01-M-004', 'BD-F01-M-005', 'BD-F01-L-004', 'BD-F01-L-005', 'BD-F01-XL-004', 'BD-F01-XL-005');

-- Thủ Đức: 6 gian kho -> 3 occupied (50%), 1 maintenance, 2 available
UPDATE storage_units SET status = 'occupied'
WHERE code IN ('HCM-TD-F01-S-001', 'HCM-TD-F01-M-001', 'HCM-TD-F01-XL-001');

UPDATE storage_units SET status = 'maintenance' WHERE code = 'HCM-TD-F01-L-001';
UPDATE storage_units SET status = 'available'   WHERE code IN ('HCM-TD-F01-L-002', 'HCM-TD-F01-XL-002');

-- Q7: 4 gian kho -> 2 occupied (50%), 1 maintenance, 1 available
UPDATE storage_units SET status = 'occupied'    WHERE code IN ('Q7-U101', 'Q7-U201');
UPDATE storage_units SET status = 'maintenance' WHERE code = 'Q7-U103';
UPDATE storage_units SET status = 'available'   WHERE code = 'Q7-U102';

-- ------------------------------------------------------------------------------
-- 4. CHÍNH SÁCH GÓI THUÊ (RENTAL PACKAGE POLICIES CHO CÁC CƠ SỞ)
-- ------------------------------------------------------------------------------
INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-1111-0001-0000-000000000001'), NOW(6), NOW(6), f.id, 'PKG-Q1-1M', 'Gói Linh Hoạt 1 Tháng Q1', 1, 0.0000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-Q1-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-1111-0003-0000-000000000003'), NOW(6), NOW(6), f.id, 'PKG-Q1-3M', 'Gói Tiết Kiệm 3 Tháng Q1 (Giảm 5%)', 3, 0.0500, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-Q1-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-1111-0006-0000-000000000006'), NOW(6), NOW(6), f.id, 'PKG-Q1-6M', 'Gói Tiêu Chuẩn 6 Tháng Q1 (Giảm 10%)', 6, 0.1000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-Q1-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-1111-0012-0000-000000000012'), NOW(6), NOW(6), f.id, 'PKG-Q1-12M', 'Gói Dài Hạn 12 Tháng Q1 (Giảm 15%)', 12, 0.1500, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-Q1-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-8888-0001-0000-000000000001'), NOW(6), NOW(6), f.id, 'PKG-BD-1M', 'Gói Linh Hoạt 1 Tháng BD', 1, 0.0000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='BD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-8888-0003-0000-000000000003'), NOW(6), NOW(6), f.id, 'PKG-BD-3M', 'Gói Tiết Kiệm 3 Tháng BD (Giảm 5%)', 3, 0.0500, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='BD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-8888-0006-0000-000000000006'), NOW(6), NOW(6), f.id, 'PKG-BD-6M', 'Gói Tiêu Chuẩn 6 Tháng BD (Giảm 10%)', 6, 0.1000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='BD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-8888-0012-0000-000000000012'), NOW(6), NOW(6), f.id, 'PKG-BD-12M', 'Gói Dài Hạn 12 Tháng BD (Giảm 15%)', 12, 0.1500, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='BD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-9999-0001-0000-000000000001'), NOW(6), NOW(6), f.id, 'PKG-TD-1M', 'Gói Linh Hoạt 1 Tháng Thủ Đức', 1, 0.0000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-TD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-9999-0003-0000-000000000003'), NOW(6), NOW(6), f.id, 'PKG-TD-3M', 'Gói Tiết Kiệm 3 Tháng Thủ Đức (Giảm 5%)', 3, 0.0500, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-TD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

INSERT INTO rental_package_policies (id, created_at, updated_at, facility_id, code, name, rental_months, discount_rate, policy_version, effective_from, effective_to, active)
SELECT UUID_TO_BIN('dddddddd-9999-0006-0000-000000000006'), NOW(6), NOW(6), f.id, 'PKG-TD-6M', 'Gói Tiêu Chuẩn 6 Tháng Thủ Đức (Giảm 10%)', 6, 0.1000, 'v1.0', '2026-01-01', '2026-12-31', 1 FROM facilities f WHERE f.code='HCM-TD-F01'
ON DUPLICATE KEY UPDATE name=VALUES(name), discount_rate=VALUES(discount_rate), active=1;

-- ------------------------------------------------------------------------------
-- 5. DỌN DẸP & TẠO ĐƠN ĐẶT KHO TEST (BO RESERVATIONS & QUOTES)
-- ------------------------------------------------------------------------------
DELETE FROM return_cases WHERE id IN (
    UUID_TO_BIN('ee000001-1111-0001-0000-000000000001'),
    UUID_TO_BIN('ee000001-8888-0001-0000-000000000001')
);
DELETE FROM maintenance_tasks WHERE id IN (
    UUID_TO_BIN('ff000001-1111-0001-0000-000000000001'),
    UUID_TO_BIN('ff000001-7777-0001-0000-000000000001'),
    UUID_TO_BIN('ff000001-8888-0001-0000-000000000001'),
    UUID_TO_BIN('ff000001-9999-0001-0000-000000000001')
);
DELETE FROM payments WHERE idempotency_key LIKE 'PAY-BO-%';
DELETE FROM rentals WHERE id IN (
    UUID_TO_BIN('aaaaaaaa-b001-0001-0000-000000000001'),
    UUID_TO_BIN('aaaaaaaa-b001-0002-0000-000000000002'),
    UUID_TO_BIN('aaaaaaaa-b001-0003-0000-000000000003'),
    UUID_TO_BIN('aaaaaaaa-b001-0004-0000-000000000004')
);
DELETE FROM reservations WHERE id IN (
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'),
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'),
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'),
    UUID_TO_BIN('99999999-b001-0004-0000-000000000004')
);
DELETE FROM reservation_quotes WHERE id IN (
    UUID_TO_BIN('88888888-b001-0001-0000-000000000001'),
    UUID_TO_BIN('88888888-b001-0002-0000-000000000002'),
    UUID_TO_BIN('88888888-b001-0003-0000-000000000003'),
    UUID_TO_BIN('88888888-b001-0004-0000-000000000004')
);

-- Quotes với UUID riêng
INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
)
SELECT
    UUID_TO_BIN('88888888-b001-0001-0000-000000000001'), '2026-05-01 08:00:00', '2026-05-01 08:00:00',
    u.id, f.id, ut.id,
    'PKG-Q1-6M', 'v1.0', '2026-05-01', '2026-10-31', 6,
    9500000.00, 57000000.00, 0.1000, 5700000.00, 51300000.00,
    9500000.00, 9500000.00, 41800000.00, 51300000.00, 60800000.00,
    '2026-05-01 08:00:00', '2026-06-01 08:00:00'
FROM users u, facilities f, unit_types ut
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q1-F01' AND ut.facility_id=f.id AND ut.code='M' LIMIT 1;

INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
)
SELECT
    UUID_TO_BIN('88888888-b001-0002-0000-000000000002'), '2026-06-01 08:00:00', '2026-06-01 08:00:00',
    u.id, f.id, ut.id,
    'PKG-Q7-6M', 'v1.0', '2026-06-01', '2026-11-30', 6,
    7000000.00, 42000000.00, 0.1000, 4200000.00, 37800000.00,
    7000000.00, 7000000.00, 30800000.00, 37800000.00, 44800000.00,
    '2026-06-01 08:00:00', '2026-07-01 08:00:00'
FROM users u, facilities f, unit_types ut
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q7-F01' AND ut.facility_id=f.id AND ut.code='Q7-UT-M' LIMIT 1;

INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
)
SELECT
    UUID_TO_BIN('88888888-b001-0003-0000-000000000003'), '2026-07-01 08:00:00', '2026-07-01 08:00:00',
    u.id, f.id, ut.id,
    'PKG-BD-6M', 'v1.0', '2026-07-01', '2026-12-31', 6,
    15000000.00, 90000000.00, 0.1000, 9000000.00, 81000000.00,
    15000000.00, 15000000.00, 66000000.00, 81000000.00, 96000000.00,
    '2026-07-01 08:00:00', '2026-08-01 08:00:00'
FROM users u, facilities f, unit_types ut
WHERE u.email='customer@storagehub.demo' AND f.code='BD-F01' AND ut.facility_id=f.id AND ut.code='L' LIMIT 1;

INSERT INTO reservation_quotes (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    pricing_package_code, policy_version, start_date, end_date, rental_months,
    monthly_price, subtotal, discount_rate, discount_amount, total_after_discount,
    reservation_deposit_amount, security_deposit_amount, remaining_rental_amount,
    due_at_check_in, total_initial_obligation, quoted_at, expires_at
)
SELECT
    UUID_TO_BIN('88888888-b001-0004-0000-000000000004'), '2026-08-01 08:00:00', '2026-08-01 08:00:00',
    u.id, f.id, ut.id,
    'PKG-TD-3M', 'v1.0', '2026-08-01', '2026-10-31', 3,
    2000000.00, 6000000.00, 0.0500, 300000.00, 5700000.00,
    2000000.00, 2000000.00, 3700000.00, 5700000.00, 7700000.00,
    '2026-08-01 08:00:00', '2026-09-01 08:00:00'
FROM users u, facilities f, unit_types ut
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-TD-F01' AND ut.facility_id=f.id AND ut.code='M' LIMIT 1;

-- Reservations với UUID riêng
INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
)
SELECT
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), '2026-05-01 08:30:00', '2026-05-01 08:30:00',
    u.id, f.id, ut.id, su.id, UUID_TO_BIN('88888888-b001-0001-0000-000000000001'),
    'RES-BO-Q1-001', '2026-05-01', '2026-10-31',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 14.0, 750.0,
    51300000.00, 'IDEMP-RES-BO-Q1-001', 1
FROM users u, facilities f, unit_types ut, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q1-F01' AND ut.facility_id=f.id AND ut.code='M' AND su.code='HCM-Q1-F01-M-001' LIMIT 1;

INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
)
SELECT
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), '2026-06-01 08:30:00', '2026-06-01 08:30:00',
    u.id, f.id, ut.id, su.id, UUID_TO_BIN('88888888-b001-0002-0000-000000000002'),
    'RES-BO-Q7-001', '2026-06-01', '2026-11-30',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 12.5, 600.0,
    37800000.00, 'IDEMP-RES-BO-Q7-001', 1
FROM users u, facilities f, unit_types ut, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q7-F01' AND ut.facility_id=f.id AND ut.code='Q7-UT-M' AND su.code='Q7-U101' LIMIT 1;

INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
)
SELECT
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'), '2026-07-01 08:30:00', '2026-07-01 08:30:00',
    u.id, f.id, ut.id, su.id, UUID_TO_BIN('88888888-b001-0003-0000-000000000003'),
    'RES-BO-BD-001', '2026-07-01', '2026-12-31',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 25.0, 1200.0,
    81000000.00, 'IDEMP-RES-BO-BD-001', 1
FROM users u, facilities f, unit_types ut, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='BD-F01' AND ut.facility_id=f.id AND ut.code='L' AND su.code='BD-F01-L-001' LIMIT 1;

INSERT INTO reservations (
    id, created_at, updated_at, customer_id, facility_id, unit_type_id,
    assigned_unit_id, source_quote_id, reservation_code, start_date, end_date,
    status, compatibility_result, goods_review_status, total_goods_volume_m3,
    total_goods_weight_kg, amount, idempotency_key, version
)
SELECT
    UUID_TO_BIN('99999999-b001-0004-0000-000000000004'), '2026-08-01 08:30:00', '2026-08-01 08:30:00',
    u.id, f.id, ut.id, su.id, UUID_TO_BIN('88888888-b001-0004-0000-000000000004'),
    'RES-BO-TD-001', '2026-08-01', '2026-10-31',
    'COMPLETED', 'COMPATIBLE', 'APPROVED', 15.0, 700.0,
    5700000.00, 'IDEMP-RES-BO-TD-001', 1
FROM users u, facilities f, unit_types ut, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-TD-F01' AND ut.facility_id=f.id AND ut.code='M' AND su.code='HCM-TD-F01-M-001' LIMIT 1;

-- ------------------------------------------------------------------------------
-- 6. GIAO DỊCH THANH TOÁN DOANH THU 6 THÁNG (PAYMENTS TỪ 05/2026 ĐẾN 10/2026)
-- ------------------------------------------------------------------------------
-- Tháng 05/2026: Q1 (Tổng ~ 51.3 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b005-0001-0000-000000000001'), '2026-05-02 09:15:00', '2026-05-02 09:15:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    9500000.00, 'VND', 'PAY-BO-202605-001', '2026-05-02 09:15:00', '2026-05-02 09:15:00',
    'RESERVATION_DEPOSIT', 'PAID', 1, 'INTENT-BO-202605-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b005-0002-0000-000000000002'), '2026-05-03 14:30:00', '2026-05-03 14:30:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    41800000.00, 'VND', 'PAY-BO-202605-002', '2026-05-03 14:30:00', '2026-05-03 14:30:00',
    'RENTAL_BALANCE', 'PAID', 1, 'INTENT-BO-202605-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- Tháng 06/2026: Q7 & Q1 (Tổng ~ 66.3 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b006-0001-0000-000000000001'), '2026-06-05 10:00:00', '2026-06-05 10:00:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    7000000.00, 'VND', 'PAY-BO-202606-001', '2026-06-05 10:00:00', '2026-06-05 10:00:00',
    'RESERVATION_DEPOSIT', 'PAID', 1, 'INTENT-BO-202606-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b006-0002-0000-000000000002'), '2026-06-06 16:45:00', '2026-06-06 16:45:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    30800000.00, 'VND', 'PAY-BO-202606-002', '2026-06-06 16:45:00', '2026-06-06 16:45:00',
    'RENTAL_BALANCE', 'PAID', 1, 'INTENT-BO-202606-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b006-0003-0000-000000000003'), '2026-06-20 11:20:00', '2026-06-20 11:20:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    28500000.00, 'VND', 'PAY-BO-202606-003', '2026-06-20 11:20:00', '2026-06-20 11:20:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202606-003'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- Tháng 07/2026: BD & Q7 (Tổng ~ 102 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b007-0001-0000-000000000001'), '2026-07-04 09:30:00', '2026-07-04 09:30:00',
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'), u.id,
    15000000.00, 'VND', 'PAY-BO-202607-001', '2026-07-04 09:30:00', '2026-07-04 09:30:00',
    'RESERVATION_DEPOSIT', 'PAID', 1, 'INTENT-BO-202607-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b007-0002-0000-000000000002'), '2026-07-05 15:00:00', '2026-07-05 15:00:00',
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'), u.id,
    66000000.00, 'VND', 'PAY-BO-202607-002', '2026-07-05 15:00:00', '2026-07-05 15:00:00',
    'RENTAL_BALANCE', 'PAID', 1, 'INTENT-BO-202607-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b007-0003-0000-000000000003'), '2026-07-18 10:15:00', '2026-07-18 10:15:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    21000000.00, 'VND', 'PAY-BO-202607-003', '2026-07-18 10:15:00', '2026-07-18 10:15:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202607-003'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- Tháng 08/2026: Thủ Đức, Q1 & Q7 (Tổng ~ 118.7 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b008-0001-0000-000000000001'), '2026-08-03 08:45:00', '2026-08-03 08:45:00',
    UUID_TO_BIN('99999999-b001-0004-0000-000000000004'), u.id,
    2000000.00, 'VND', 'PAY-BO-202608-001', '2026-08-03 08:45:00', '2026-08-03 08:45:00',
    'RESERVATION_DEPOSIT', 'PAID', 1, 'INTENT-BO-202608-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b008-0002-0000-000000000002'), '2026-08-04 11:30:00', '2026-08-04 11:30:00',
    UUID_TO_BIN('99999999-b001-0004-0000-000000000004'), u.id,
    3700000.00, 'VND', 'PAY-BO-202608-002', '2026-08-04 11:30:00', '2026-08-04 11:30:00',
    'RENTAL_BALANCE', 'PAID', 1, 'INTENT-BO-202608-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b008-0003-0000-000000000003'), '2026-08-15 14:00:00', '2026-08-15 14:00:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    65000000.00, 'VND', 'PAY-BO-202608-003', '2026-08-15 14:00:00', '2026-08-15 14:00:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202608-003'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b008-0004-0000-000000000004'), '2026-08-25 16:20:00', '2026-08-25 16:20:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    48000000.00, 'VND', 'PAY-BO-202608-004', '2026-08-25 16:20:00', '2026-08-25 16:20:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202608-004'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- Tháng 09/2026: Đầy đủ các cơ sở + Damage Fee (Tổng ~ 160.5 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b009-0001-0000-000000000001'), '2026-09-05 10:10:00', '2026-09-05 10:10:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    75000000.00, 'VND', 'PAY-BO-202609-001', '2026-09-05 10:10:00', '2026-09-05 10:10:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202609-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b009-0002-0000-000000000002'), '2026-09-12 11:30:00', '2026-09-12 11:30:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    48000000.00, 'VND', 'PAY-BO-202609-002', '2026-09-12 11:30:00', '2026-09-12 11:30:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202609-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b009-0003-0000-000000000003'), '2026-09-22 15:45:00', '2026-09-22 15:45:00',
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'), u.id,
    35000000.00, 'VND', 'PAY-BO-202609-003', '2026-09-22 15:45:00', '2026-09-22 15:45:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202609-003'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b009-0004-0000-000000000004'), '2026-09-28 16:00:00', '2026-09-28 16:00:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    2500000.00, 'VND', 'PAY-BO-202609-004', '2026-09-28 16:00:00', '2026-09-28 16:00:00',
    'DAMAGE_FEE', 'PAID', 1, 'INTENT-BO-202609-004'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- Tháng 10/2026: Đỉnh doanh thu (~ 186.5 triệu)
INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b010-0001-0000-000000000001'), '2026-10-02 09:00:00', '2026-10-02 09:00:00',
    UUID_TO_BIN('99999999-b001-0001-0000-000000000001'), u.id,
    78000000.00, 'VND', 'PAY-BO-202610-001', '2026-10-02 09:00:00', '2026-10-02 09:00:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202610-001'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b010-0002-0000-000000000002'), '2026-10-04 14:15:00', '2026-10-04 14:15:00',
    UUID_TO_BIN('99999999-b001-0002-0000-000000000002'), u.id,
    52000000.00, 'VND', 'PAY-BO-202610-002', '2026-10-04 14:15:00', '2026-10-04 14:15:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202610-002'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b010-0003-0000-000000000003'), '2026-10-06 11:45:00', '2026-10-06 11:45:00',
    UUID_TO_BIN('99999999-b001-0003-0000-000000000003'), u.id,
    38000000.00, 'VND', 'PAY-BO-202610-003', '2026-10-06 11:45:00', '2026-10-06 11:45:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202610-003'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

INSERT INTO payments (
    id, created_at, updated_at, reservation_id, initiated_by, amount, currency,
    idempotency_key, paid_at, processed_at, purpose, status, version, gateway_intent_id
)
SELECT
    UUID_TO_BIN('cccccccc-b010-0004-0000-000000000004'), '2026-10-08 16:30:00', '2026-10-08 16:30:00',
    UUID_TO_BIN('99999999-b001-0004-0000-000000000004'), u.id,
    18500000.00, 'VND', 'PAY-BO-202610-004', '2026-10-08 16:30:00', '2026-10-08 16:30:00',
    'MONTHLY_RENT', 'PAID', 1, 'INTENT-BO-202610-004'
FROM users u WHERE u.email='customer@storagehub.demo' LIMIT 1;

-- ------------------------------------------------------------------------------
-- 7. HỢP ĐỒNG THUÊ ĐANG KÍCH HOẠT (ACTIVE RENTALS)
-- ------------------------------------------------------------------------------
INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
)
SELECT
    UUID_TO_BIN('aaaaaaaa-b001-0001-0000-000000000001'), '2026-05-01 00:00:00', NOW(6),
    u.id, f.id, su.id, UUID_TO_BIN('99999999-b001-0001-0000-000000000001'),
    'active', '2026-05-01', '2026-11-30', 9500000.00
FROM users u, facilities f, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q1-F01' AND su.code='HCM-Q1-F01-M-001' LIMIT 1;

INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
)
SELECT
    UUID_TO_BIN('aaaaaaaa-b001-0002-0000-000000000002'), '2026-05-01 00:00:00', NOW(6),
    u.id, f.id, su.id, UUID_TO_BIN('99999999-b001-0001-0000-000000000001'),
    'active', '2026-05-01', '2026-11-30', 15000000.00
FROM users u, facilities f, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q1-F01' AND su.code='HCM-Q1-F01-L-001' LIMIT 1;

INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
)
SELECT
    UUID_TO_BIN('aaaaaaaa-b001-0003-0000-000000000003'), '2026-07-01 00:00:00', NOW(6),
    u.id, f.id, su.id, UUID_TO_BIN('99999999-b001-0003-0000-000000000003'),
    'active', '2026-07-01', '2027-01-31', 15000000.00
FROM users u, facilities f, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='BD-F01' AND su.code='BD-F01-L-001' LIMIT 1;

INSERT INTO rentals (
    id, created_at, updated_at, customer_id, facility_id, storage_unit_id,
    reservation_id, status, start_date, contract_end_date, monthly_price
)
SELECT
    UUID_TO_BIN('aaaaaaaa-b001-0004-0000-000000000004'), '2026-08-01 00:00:00', NOW(6),
    u.id, f.id, su.id, UUID_TO_BIN('99999999-b001-0004-0000-000000000004'),
    'active', '2026-08-01', '2026-11-30', 2000000.00
FROM users u, facilities f, storage_units su
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-TD-F01' AND su.code='HCM-TD-F01-M-001' LIMIT 1;

-- ------------------------------------------------------------------------------
-- 8. HỒ SƠ TRẢ KHO ĐANG XỬ LÝ (OPEN RETURN CASES)
-- ------------------------------------------------------------------------------
INSERT INTO return_cases (
    id, created_at, updated_at, facility_id, storage_unit_id, customer_id, rental_id,
    requested_by, scheduled_date, status, deposit_amount, damage_fee, cleaning_fee,
    lost_item_fee, outstanding_fee, overdue_fee, total_deductions, net_refund_amount, amount_due_from_customer,
    customer_confirmed, returned_key, returned_card, returned_lock, requested_at, customer_notes
)
SELECT
    UUID_TO_BIN('ee000001-1111-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, u.id, r.id, u.id,
    '2026-10-15', 'scheduled', 9500000.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 9500000.00, 0.00,
    0, 0, 0, 0, NOW(6), 'Hết hạn hợp đồng, đề nghị trả kho đúng lịch'
FROM users u, facilities f, storage_units su, rentals r
WHERE u.email='customer@storagehub.demo' AND f.code='HCM-Q1-F01' AND su.code='HCM-Q1-F01-M-001' AND r.id=UUID_TO_BIN('aaaaaaaa-b001-0001-0000-000000000001') LIMIT 1;

INSERT INTO return_cases (
    id, created_at, updated_at, facility_id, storage_unit_id, customer_id, rental_id,
    requested_by, scheduled_date, status, deposit_amount, damage_fee, cleaning_fee,
    lost_item_fee, outstanding_fee, overdue_fee, total_deductions, net_refund_amount, amount_due_from_customer,
    customer_confirmed, returned_key, returned_card, returned_lock, requested_at, customer_notes
)
SELECT
    UUID_TO_BIN('ee000001-8888-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, u.id, r.id, u.id,
    '2026-10-12', 'inspected', 15000000.00, 500000.00, 200000.00, 0.00, 0.00, 0.00, 700000.00, 14300000.00, 0.00,
    0, 1, 1, 1, NOW(6), 'Đã kiểm tra kho, đang chờ khách xác nhận quyết toán cọc'
FROM users u, facilities f, storage_units su, rentals r
WHERE u.email='customer@storagehub.demo' AND f.code='BD-F01' AND su.code='BD-F01-L-001' AND r.id=UUID_TO_BIN('aaaaaaaa-b001-0003-0000-000000000003') LIMIT 1;

-- ------------------------------------------------------------------------------
-- 9. TÁC VỤ BẢO TRÌ ĐANG THỰC HIỆN (OPEN MAINTENANCE TASKS)
-- ------------------------------------------------------------------------------
INSERT INTO maintenance_tasks (
    id, created_at, updated_at, facility_id, storage_unit_id, reported_by, assigned_to,
    title, reason, priority, status, damage_classification, due_at
)
SELECT
    UUID_TO_BIN('ff000001-1111-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, mgr.id, stf.id,
    'Sơn dặm và gia cố bản lề cửa kho Q1-L-004',
    'Bản lề rỉ sét do độ ẩm mùa mưa, cần xịt chống rỉ và tra dầu mỡ',
    'medium', 'in_progress', 'minor_damage', '2026-10-15'
FROM facilities f, storage_units su, users mgr, users stf
WHERE f.code='HCM-Q1-F01' AND su.code='HCM-Q1-F01-L-004' AND mgr.email='manager@storagehub.demo' AND stf.email='staff@storagehub.demo' LIMIT 1;

INSERT INTO maintenance_tasks (
    id, created_at, updated_at, facility_id, storage_unit_id, reported_by, assigned_to,
    title, reason, priority, status, damage_classification, due_at
)
SELECT
    UUID_TO_BIN('ff000001-7777-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, mgr.id, stf.id,
    'Kiểm tra cảm biến nhiệt độ & PCCC gian Q7-U103',
    'Cảm biến khói báo lỗi kết nối dây tín hiệu',
    'high', 'open', 'no_damage', '2026-10-12'
FROM facilities f, storage_units su, users mgr, users stf
WHERE f.code='HCM-Q7-F01' AND su.code='Q7-U103' AND mgr.email='manager@storagehub.demo' AND stf.email='staff@storagehub.demo' LIMIT 1;

INSERT INTO maintenance_tasks (
    id, created_at, updated_at, facility_id, storage_unit_id, reported_by, assigned_to,
    title, reason, priority, status, damage_classification, due_at
)
SELECT
    UUID_TO_BIN('ff000001-8888-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, mgr.id, stf.id,
    'Thay thế ổ khóa điện tử RFID kho BD-L-003',
    'Đầu đọc RFID chập chờn khi quẹt thẻ từ xa',
    'medium', 'open', 'minor_damage', '2026-10-16'
FROM facilities f, storage_units su, users mgr, users stf
WHERE f.code='BD-F01' AND su.code='BD-F01-L-003' AND mgr.email='manager@storagehub.demo' AND stf.email='staff@storagehub.demo' LIMIT 1;

INSERT INTO maintenance_tasks (
    id, created_at, updated_at, facility_id, storage_unit_id, reported_by, assigned_to,
    title, reason, priority, status, damage_classification, due_at
)
SELECT
    UUID_TO_BIN('ff000001-9999-0001-0000-000000000001'), NOW(6), NOW(6),
    f.id, su.id, mgr.id, stf.id,
    'Sửa chữa đèn trần LED và máng cáp TD-L-001',
    'Đèn LED nhấp nháy, cần thay thế ballast nguồn',
    'low', 'in_progress', 'minor_damage', '2026-10-14'
FROM facilities f, storage_units su, users mgr, users stf
WHERE f.code='HCM-TD-F01' AND su.code='HCM-TD-F01-L-001' AND mgr.email='manager@storagehub.demo' AND stf.email='staff@storagehub.demo' LIMIT 1;

COMMIT;
