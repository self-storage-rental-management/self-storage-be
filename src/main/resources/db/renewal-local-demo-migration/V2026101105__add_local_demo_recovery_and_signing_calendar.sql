-- Local-demo-only authoritative Recovery registry and facility signing calendar.
-- Recovery entries are created through the overdue handoff service. The signing calendar
-- is independent of Storage Reservation/Check-in appointments and uses DB-backed locking.
CREATE TABLE renewal_demo_recovery_cases (
    id BINARY(16) NOT NULL,
    case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_demo_recovery_case_ref UNIQUE (case_ref),
    CONSTRAINT fk_renewal_demo_recovery_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_renewal_demo_recovery_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT ck_renewal_demo_recovery_status CHECK (status IN ('ACTIVE','RESOLVED','CANCELLED'))
) ENGINE=InnoDB;
CREATE INDEX ix_renewal_demo_recovery_rental_state
    ON renewal_demo_recovery_cases (rental_id, status, received_at);

CREATE TABLE renewal_demo_signing_appointments (
    id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    renewal_id BINARY(16) NOT NULL,
    appointment_start TIMESTAMP(6) NOT NULL,
    appointment_end TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_demo_signing_renewal UNIQUE (renewal_id),
    CONSTRAINT fk_renewal_demo_signing_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_renewal_demo_signing_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id),
    CONSTRAINT ck_renewal_demo_signing_interval CHECK (appointment_end > appointment_start),
    CONSTRAINT ck_renewal_demo_signing_status CHECK (status IN ('RESERVED','RELEASED'))
) ENGINE=InnoDB;
CREATE INDEX ix_renewal_demo_signing_capacity
    ON renewal_demo_signing_appointments (facility_id, status, appointment_start, appointment_end);
