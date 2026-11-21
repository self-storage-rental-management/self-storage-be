-- The security deposit is one month of rent. Repair priced legacy rows that
-- were saved before the pricing flow enforced that rule.
UPDATE unit_types
SET security_deposit_amount = monthly_price
WHERE monthly_price > 0.00
  AND security_deposit_amount <> monthly_price;
