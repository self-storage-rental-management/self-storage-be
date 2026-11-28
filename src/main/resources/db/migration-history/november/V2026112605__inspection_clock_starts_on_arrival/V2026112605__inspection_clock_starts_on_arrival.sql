-- The 72h inspection window now starts when staff confirm the customer arrived for
-- inspection, not on the first identity verification. Existing records keep the deadline
-- they already hold; only the clock start is recorded so the UI can explain it.
ALTER TABLE rentals ADD COLUMN inspection_clock_started_at DATETIME(6) NULL;

-- Preserve the original anchor for records already inside the 72h window.
UPDATE rentals
SET inspection_clock_started_at = first_identity_verification_at
WHERE inspection_clock_started_at IS NULL
  AND first_identity_verification_at IS NOT NULL;
