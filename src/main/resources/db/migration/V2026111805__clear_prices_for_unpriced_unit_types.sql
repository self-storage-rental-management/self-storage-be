-- A unit type awaiting Business pricing must not retain a previous
-- publication price or deposit. The Business flow must enter both values
-- again before publication.
UPDATE unit_types
SET monthly_price = 0.00,
    security_deposit_amount = 0.00
WHERE status IN ('draft', 'awaiting_pricing');
