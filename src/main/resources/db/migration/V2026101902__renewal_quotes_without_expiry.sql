-- Renewal quotes have no time-to-live. Existing timestamps remain historical metadata.
ALTER TABLE renewal_quotes MODIFY COLUMN expires_at TIMESTAMP(6) NULL;
