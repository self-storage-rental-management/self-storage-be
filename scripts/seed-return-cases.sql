USE storagehub_local;
SET NAMES utf8mb4;

START TRANSACTION;

-- Lấy ID của customer demo
SET @cust_id = (SELECT id FROM users WHERE email='customer@storagehub.demo' LIMIT 1);
SET @admin_id = (SELECT id FROM users WHERE email='admin@storagehub.demo' LIMIT 1);

-- Đảm bảo có reservation hợp lệ để rentals tham chiếu
SET @rsv_id = (SELECT id FROM reservations LIMIT 1);
SET @fac_id = COALESCE((SELECT id FROM facilities WHERE code='HCM-Q1-F01' LIMIT 1), (SELECT id FROM facilities LIMIT 1));

-- Lấy 3 gian kho
SET @unit1 = (SELECT id FROM storage_units WHERE facility_id=@fac_id LIMIT 1 OFFSET 0);
SET @unit2 = (SELECT id FROM storage_units WHERE facility_id=@fac_id LIMIT 1 OFFSET 1);
SET @unit3 = (SELECT id FROM storage_units WHERE facility_id=@fac_id LIMIT 1 OFFSET 2);

-- Tạo 3 Rentals
INSERT INTO rentals (id, created_at, updated_at, customer_id, facility_id, storage_unit_id, reservation_id, status, start_date, contract_end_date, monthly_price)
VALUES 
(UUID_TO_BIN('aaaaaaaa-1111-0001-0000-000000000001'), NOW(6), NOW(6), @cust_id, @fac_id, @unit1, @rsv_id, 'active', '2026-05-01', '2026-11-30', 9500000.00),
(UUID_TO_BIN('aaaaaaaa-1111-0002-0000-000000000002'), NOW(6), NOW(6), @cust_id, @fac_id, @unit2, @rsv_id, 'active', '2026-06-01', '2026-12-31', 15000000.00),
(UUID_TO_BIN('aaaaaaaa-1111-0003-0000-000000000003'), NOW(6), NOW(6), @cust_id, @fac_id, @unit3, @rsv_id, 'active', '2026-07-01', '2027-01-31', 6000000.00)
ON DUPLICATE KEY UPDATE updated_at=NOW(6);

-- 1. Ca 1: Scheduled (Chờ nhân viên Staff đến nghiệm thu)
INSERT INTO return_cases (
    id, created_at, updated_at, facility_id, storage_unit_id, customer_id, rental_id,
    requested_by, scheduled_date, status, deposit_amount, damage_fee, cleaning_fee,
    lost_item_fee, outstanding_fee, overdue_fee, total_deductions, net_refund_amount, amount_due_from_customer,
    customer_confirmed, returned_key, returned_card, returned_lock, requested_at, customer_notes
) VALUES (
    UUID_TO_BIN('ee000001-0001-0001-0000-000000000001'), NOW(6), NOW(6),
    @fac_id, @unit1, @cust_id, UUID_TO_BIN('aaaaaaaa-1111-0001-0000-000000000001'),
    @cust_id, '2026-10-18', 'scheduled', 9500000.00, 0.00, 0.00,
    0.00, 0.00, 0.00, 0.00, 9500000.00, 0.00,
    0, 0, 0, 0, NOW(6), 'Hết hợp đồng thuê kho, đề nghị nghiệm thu đúng hẹn để nhận lại cọc'
) ON DUPLICATE KEY UPDATE status='scheduled', updated_at=NOW(6);

-- 2. Ca 2: Awaiting Customer Confirmation (Đã nghiệm thu hiện trường, chờ khách duyệt)
INSERT INTO return_cases (
    id, created_at, updated_at, facility_id, storage_unit_id, customer_id, rental_id,
    requested_by, scheduled_date, status, deposit_amount, damage_fee, cleaning_fee,
    lost_item_fee, outstanding_fee, overdue_fee, total_deductions, net_refund_amount, amount_due_from_customer,
    customer_confirmed, returned_key, returned_card, returned_lock, requested_at, inspected_at,
    inventory_match, damage_classification, inspection_notes, proposed_unit_status, customer_notes
) VALUES (
    UUID_TO_BIN('ee000001-0002-0002-0000-000000000002'), NOW(6), NOW(6),
    @fac_id, @unit2, @cust_id, UUID_TO_BIN('aaaaaaaa-1111-0002-0000-000000000002'),
    @cust_id, '2026-10-15', 'awaiting_customer_confirmation', 15000000.00, 200000.00, 100000.00,
    0.00, 0.00, 0.00, 300000.00, 14700000.00, 0.00,
    0, 1, 1, 1, NOW(6), NOW(6),
    'match', 'minor_damage', 'Sàn kho xước nhẹ do kéo pallet, tường sạch sẽ, bàn giao đủ chìa và thẻ từ.',
    'available', 'Đã dọn dẹp hàng hóa sạch sẽ, bàn giao lại kho'
) ON DUPLICATE KEY UPDATE status='awaiting_customer_confirmation', updated_at=NOW(6);

-- 3. Ca 3: Disputed (Khách khiếu nại phí, CẦN BAN ĐIỀU HÀNH THẨM ĐỊNH!)
INSERT INTO return_cases (
    id, created_at, updated_at, facility_id, storage_unit_id, customer_id, rental_id,
    requested_by, scheduled_date, status, deposit_amount, damage_fee, cleaning_fee,
    lost_item_fee, outstanding_fee, overdue_fee, total_deductions, net_refund_amount, amount_due_from_customer,
    customer_confirmed, returned_key, returned_card, returned_lock, requested_at, inspected_at,
    inventory_match, damage_classification, inspection_notes, customer_decision, customer_decision_note,
    proposed_unit_status, customer_notes
) VALUES (
    UUID_TO_BIN('ee000001-0003-0003-0000-000000000003'), NOW(6), NOW(6),
    @fac_id, @unit3, @cust_id, UUID_TO_BIN('aaaaaaaa-1111-0003-0000-000000000003'),
    @cust_id, '2026-10-14', 'disputed', 6000000.00, 300000.00, 250000.00,
    0.00, 0.00, 0.00, 550000.00, 5450000.00, 0.00,
    0, 1, 1, 1, NOW(6), NOW(6),
    'match', 'minor_damage', 'Tường có vết ố nhỏ, sàn kho còn bụi bẩn.',
    'disputed', 'Tôi đã tự thuê dịch vụ vệ sinh công nghiệp trước khi trả kho, có hóa đơn đính kèm. Đề nghị Ban Điều Hành xem xét hủy bỏ phí vệ sinh 250.000đ.',
    'available', 'Đã chuyển đồ đạc ra ngoài, đề nghị kiểm tra hoàn cọc'
) ON DUPLICATE KEY UPDATE status='disputed', updated_at=NOW(6);

COMMIT;
