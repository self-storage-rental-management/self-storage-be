UPDATE facilities
SET status = 'setup'
WHERE status IN ('ready_to_publish', 'coming_soon');
