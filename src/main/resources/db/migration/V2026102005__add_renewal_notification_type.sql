-- Retain existing ENUM positions; new Renewal notifications must not roll back submission.
ALTER TABLE notifications
    MODIFY COLUMN type ENUM(
        'CHECKIN',
        'OVERDUE',
        'PAYMENT',
        'RESERVATION',
        'RETURN',
        'SUPPORT',
        'SYSTEM',
        'RENEWAL'
    ) NOT NULL;
