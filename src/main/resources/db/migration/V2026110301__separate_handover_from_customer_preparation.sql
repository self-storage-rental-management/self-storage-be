-- Keep physical handover separate from the customer's preparation period.
ALTER TABLE check_ins MODIFY COLUMN status ENUM(
    'scheduled','identity_verified','contract_signed','payment_collected',
    'handover_pending_receipt','setup_in_progress','setup_completed',
    'completed','rejected','no_show','cancelled'
) NOT NULL;
