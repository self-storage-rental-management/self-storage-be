-- Align the shared MySQL enum columns with RentalStatus and CheckInStatus.
-- Existing values remain valid; no historical row is rewritten.
ALTER TABLE rentals MODIFY COLUMN status ENUM(
    'setup','active','return_requested','return_inspection','closing','completed'
) NOT NULL;

ALTER TABLE check_ins MODIFY COLUMN status ENUM(
    'scheduled','identity_verified','contract_signed','payment_collected',
    'setup_in_progress','setup_completed','completed','rejected','no_show','cancelled'
) NOT NULL;
