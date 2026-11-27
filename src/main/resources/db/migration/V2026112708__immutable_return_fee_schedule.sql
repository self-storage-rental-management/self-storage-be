-- Keep the last configured mutable schedule as the immutable baseline for ALL
-- reservations created before the first publication. LONGTEXT avoids the
-- system_settings value/options size limits for editable descriptions.
CREATE TABLE return_fee_schedule_versions (
    revision BIGINT NOT NULL PRIMARY KEY,
    published_at TIMESTAMP(6) NULL,
    payload LONGTEXT NULL,
    published_by VARCHAR(36) NULL,
    INDEX ix_return_fee_schedule_published (published_at, revision)
);

INSERT INTO return_fee_schedule_versions (revision, published_at, payload, published_by)
VALUES (0, NULL, (SELECT setting_value FROM system_settings WHERE setting_key = 'returnFeeSchedule'), NULL);
