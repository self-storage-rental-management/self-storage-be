-- Match RenewalStatus when upgrading older databases using a native MySQL ENUM.
-- Keep the original ENUM order and append new values; existing rows are unchanged.
ALTER TABLE renewals
    MODIFY COLUMN status ENUM(
        'appointment_scheduled',
        'approved',
        'cancelled',
        'completed',
        'deposit_paid',
        'payment_expired',
        'payment_failed',
        'payment_processing',
        'pending',
        'rejected',
        'accepted',
        'full_rent_paid'
    ) NOT NULL;
