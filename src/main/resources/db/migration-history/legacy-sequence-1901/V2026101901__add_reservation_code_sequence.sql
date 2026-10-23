CREATE TABLE reservation_code_sequences (
    id INT NOT NULL PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT chk_reservation_code_sequence_value CHECK (next_value > 0)
) ENGINE=InnoDB;

INSERT INTO reservation_code_sequences (id, next_value)
SELECT 1,
       COALESCE(MAX(CAST(SUBSTRING(reservation_code, 5) AS UNSIGNED)), 0) + 1
FROM reservations
WHERE reservation_code REGEXP '^RSV-[0-9]{6}$';
