ALTER TABLE renewals
    ADD COLUMN display_sequence INT NULL;

-- Only completed renewals consume an occurrence. Legacy completed rows are fixed in
-- their original creation order; rejected, cancelled and expired requests stay unnumbered.
UPDATE renewals renewal
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY rental_id ORDER BY created_at, id) AS display_sequence
    FROM renewals
    WHERE status = 'completed'
) ranked ON ranked.id = renewal.id
SET renewal.display_sequence = ranked.display_sequence;

CREATE TABLE renewal_display_sequences (
    rental_id BINARY(16) NOT NULL,
    last_sequence INT NOT NULL,
    PRIMARY KEY (rental_id),
    CONSTRAINT fk_renewal_display_sequences_rental
        FOREIGN KEY (rental_id) REFERENCES rentals(id) ON DELETE CASCADE,
    CONSTRAINT ck_renewal_display_sequences_positive CHECK (last_sequence > 0)
) ENGINE=InnoDB;

INSERT INTO renewal_display_sequences(rental_id, last_sequence)
SELECT rental_id, MAX(display_sequence)
FROM renewals
WHERE display_sequence IS NOT NULL
GROUP BY rental_id;

ALTER TABLE renewals
    ADD CONSTRAINT ck_renewals_display_sequence
        CHECK (display_sequence IS NULL OR (display_sequence > 0 AND status = 'completed')),
    ADD UNIQUE KEY uk_renewals_rental_display_sequence (rental_id, display_sequence);
