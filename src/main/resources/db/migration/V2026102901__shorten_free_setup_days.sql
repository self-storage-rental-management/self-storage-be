UPDATE rental_package_policies
SET free_setup_days = CASE rental_months
    WHEN 3 THEN 1
    WHEN 6 THEN 3
    WHEN 12 THEN 6
    WHEN 24 THEN 6
    ELSE 0
END;
