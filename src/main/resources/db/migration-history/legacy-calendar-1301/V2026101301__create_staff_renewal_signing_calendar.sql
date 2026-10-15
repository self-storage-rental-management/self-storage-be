-- Opt-in Renewal signing calendar. Existing/demo appointments are not silently adopted.
CREATE TABLE renewal_calendar_staff_locks (
    staff_id BINARY(16) NOT NULL PRIMARY KEY,
    CONSTRAINT fk_renewal_calendar_lock_staff FOREIGN KEY (staff_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE renewal_signing_reservations (
    renewal_id BINARY(16) NOT NULL PRIMARY KEY,
    facility_id BINARY(16) NOT NULL,
    staff_id BINARY(16) NOT NULL,
    accepted_quote_id BINARY(16) NOT NULL,
    appointment_start TIMESTAMP(6) NOT NULL,
    appointment_end TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_renewal_calendar_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id),
    CONSTRAINT fk_renewal_calendar_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_renewal_calendar_staff FOREIGN KEY (staff_id) REFERENCES users(id),
    CONSTRAINT ck_renewal_calendar_status CHECK (status='RESERVED' OR status='RELEASED'),
    CONSTRAINT ck_renewal_calendar_interval CHECK (appointment_end > appointment_start)
) ENGINE=InnoDB;
CREATE INDEX ix_renewal_calendar_staff_interval
    ON renewal_signing_reservations (staff_id,status,appointment_start,appointment_end);
CREATE INDEX ix_renewal_calendar_facility_interval
    ON renewal_signing_reservations (facility_id,status,appointment_start,appointment_end);
