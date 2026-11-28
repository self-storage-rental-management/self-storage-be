-- Replace only the old built-in demo dimensions. Manager-defined custom types
-- keep their values and are not part of this migration.
UPDATE unit_types
SET length_m = 12.00, width_m = 11.60, height_m = 5.00, aisle_width_m = 3.00,
    max_load_kg = 600.00
WHERE code = 'S' AND name = 'Kho Nhỏ (S)'
  AND length_m = 8.00 AND width_m = 10.00;

UPDATE unit_types
SET length_m = 17.30, width_m = 11.80, height_m = 5.00, aisle_width_m = 3.20,
    max_load_kg = 1200.00
WHERE code = 'M' AND name = 'Kho Trung (M)'
  AND length_m = 12.60 AND width_m = 10.40;

UPDATE unit_types
SET length_m = 23.00, width_m = 12.00, height_m = 5.00, aisle_width_m = 3.40,
    max_load_kg = 2400.00
WHERE code = 'L' AND name = 'Kho Lớn (L)'
  AND length_m = 18.30 AND width_m = 10.80;

UPDATE unit_types
SET length_m = 29.10, width_m = 12.20, height_m = 5.00, aisle_width_m = 3.60,
    max_load_kg = 3600.00
WHERE code = 'XL' AND name = 'Kho Rất Lớn (XL)'
  AND length_m = 25.00 AND width_m = 11.20;
