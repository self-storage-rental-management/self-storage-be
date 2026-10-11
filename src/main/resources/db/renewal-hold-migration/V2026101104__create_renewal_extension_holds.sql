-- OPT-IN only; not in default Flyway locations. No changes/backfill to shared tables.
CREATE TABLE renewal_extension_holds (
    id BINARY(16) NOT NULL PRIMARY KEY,
    renewal_id BINARY(16) NOT NULL,
    rental_id BINARY(16) NOT NULL,
    unit_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NOT NULL,
    start_date DATE NOT NULL,
    end_exclusive DATE NOT NULL,
    initial_expires_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_renewal_extension_hold UNIQUE (renewal_id),
    CONSTRAINT fk_extension_hold_renewal FOREIGN KEY (renewal_id) REFERENCES renewals(id),
    CONSTRAINT fk_extension_hold_rental FOREIGN KEY (rental_id) REFERENCES rentals(id),
    CONSTRAINT fk_extension_hold_unit FOREIGN KEY (unit_id) REFERENCES storage_units(id),
    CONSTRAINT fk_extension_hold_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT ck_extension_hold_dates CHECK (end_exclusive > start_date),
    CONSTRAINT ck_extension_hold_state CHECK (state IN ('ACTIVE','CONSUMED','RELEASED')),
    CONSTRAINT ck_extension_hold_expiry CHECK (initial_expires_at > created_at AND expires_at > created_at)
);
CREATE INDEX ix_extension_hold_unit_interval ON renewal_extension_holds (unit_id,state,start_date,end_exclusive);
