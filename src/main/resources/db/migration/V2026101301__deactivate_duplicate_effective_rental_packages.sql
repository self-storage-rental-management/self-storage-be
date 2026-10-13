-- Legacy cleanup for data created before the one-package-per-duration rule.
-- Keep the most recently effective row for each facility/duration today and
-- deactivate older duplicates without deleting their historical records.
UPDATE rental_package_policies policy
JOIN (
    SELECT id
    FROM (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY facility_id, rental_months
                ORDER BY effective_from DESC, updated_at DESC, id DESC
            ) AS duplicate_rank
        FROM rental_package_policies
        WHERE active = TRUE
          AND effective_from <= CURRENT_DATE
          AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)
    ) ranked
    WHERE ranked.duplicate_rank > 1
) duplicates ON duplicates.id = policy.id
SET policy.active = FALSE;
