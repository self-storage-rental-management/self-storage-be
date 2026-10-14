ALTER TABLE unit_types
    ADD COLUMN description VARCHAR(500) NULL AFTER name,
    ADD COLUMN aisle_width_m DECIMAL(10,2) NULL AFTER rack_height_m,
    ADD COLUMN trolley_type VARCHAR(160) NULL AFTER aisle_width_m,
    ADD COLUMN small_box_capacity INT NULL AFTER trolley_type,
    ADD COLUMN large_box_capacity INT NULL AFTER small_box_capacity;

UPDATE unit_types ut
JOIN facilities f ON f.id = ut.facility_id
SET ut.description = CASE ut.code
        WHEN 'S' THEN 'Phù hợp đồ gia dụng nhẹ và thùng carton'
        WHEN 'M' THEN 'Phù hợp thiết bị văn phòng và nội thất vừa'
        WHEN 'L' THEN 'Phù hợp pallet hàng và tồn kho kinh doanh'
        WHEN 'XL' THEN 'Phù hợp máy móc, hàng nặng và pallet công nghiệp'
    END,
    ut.aisle_width_m = CASE ut.code WHEN 'S' THEN 1.80 WHEN 'M' THEN 2.20 WHEN 'L' THEN 2.60 WHEN 'XL' THEN 3.00 END,
    ut.trolley_type = CASE ut.code
        WHEN 'S' THEN 'Xe đẩy tay / xe sàn nhỏ'
        WHEN 'M' THEN 'Xe đẩy sàn phẳng'
        WHEN 'L' THEN 'Xe nâng tay pallet'
        WHEN 'XL' THEN 'Xe nâng điện dắt bộ'
    END,
    ut.small_box_capacity = CASE ut.code WHEN 'S' THEN 336 WHEN 'M' THEN 504 WHEN 'L' THEN 672 WHEN 'XL' THEN 840 END,
    ut.large_box_capacity = CASE ut.code WHEN 'S' THEN 200 WHEN 'M' THEN 300 WHEN 'L' THEN 400 WHEN 'XL' THEN 500 END,
    ut.image_url = CASE ut.code
        WHEN 'S' THEN '/images/customer-units/kho-s.jpg'
        WHEN 'M' THEN '/images/customer-units/kho-m.jpg'
        WHEN 'L' THEN '/images/customer-units/kho-l.jpg'
        WHEN 'XL' THEN '/images/customer-units/kho-xl.jpg'
    END
WHERE f.code IN ('HCM-Q1-F01', 'BD-F01')
  AND ut.code IN ('S', 'M', 'L', 'XL');
