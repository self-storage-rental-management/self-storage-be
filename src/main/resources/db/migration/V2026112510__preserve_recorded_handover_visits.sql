-- Staff recorded these older visits before arrival tracking was available.
UPDATE reservations r
SET handover_arrived_at = COALESCE(
    r.identity_documents_received_at,
    r.identity_supplement_requested_at,
    (SELECT MIN(a.created_at)
     FROM check_ins c JOIN activity_logs a ON a.entity_id = c.id
     WHERE c.reservation_id = r.id AND a.entity_type = 'CheckIn'
       AND a.action = 'CHECKIN_IDENTITY_HOLD'),
    r.updated_at
)
WHERE r.handover_arrived_at IS NULL
  AND (r.identity_documents_received_at IS NOT NULL OR r.identity_supplement_requested_at IS NOT NULL
       OR r.identity_hold_reason IS NOT NULL);