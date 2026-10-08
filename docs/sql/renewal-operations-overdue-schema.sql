-- REVIEW ONLY, 08/10/2026. NOT enabled Flyway, NOT executed on local/team DB.
-- Prerequisite: owner-reviewed D2 tables. Confirm UUID/time/naming/collation against target schema.
-- No existing/shared table ALTER, record backfill, policy seed or DELETE.
CREATE TABLE renewal_operation_states (
 id BINARY(16) PRIMARY KEY,
 phase VARCHAR(24) NOT NULL,
 deposit_payment_ref BINARY(16) NULL, deposit_paid_at DATETIME(6) NULL,
 original_signing_deadline DATETIME(6) NULL, effective_signing_deadline DATETIME(6) NULL,
 recovery_cutoff DATETIME(6) NULL, appointment_ref BINARY(16) NULL,
 appointment_start DATETIME(6) NULL, appointment_end DATETIME(6) NULL,
 arrival_ref BINARY(16) NULL, confirmed_exception_ref BINARY(16) NULL, pending_exception_ref BINARY(16) NULL,
 completed_by BINARY(16) NULL, completed_at DATETIME(6) NULL,
 policy_ref VARCHAR(200) NULL, policy_version VARCHAR(100) NULL, version BIGINT NOT NULL,
 FOREIGN KEY (id) REFERENCES renewals(id)
);
CREATE TABLE renewal_operation_events (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 renewal_id BINARY(16) NOT NULL, kind VARCHAR(32) NOT NULL,
 occurred_at DATETIME(6) NOT NULL, actor_id BINARY(16) NULL, payload_json LONGTEXT NOT NULL,
 INDEX idx_renewal_event_timeline (renewal_id,kind,occurred_at),
 FOREIGN KEY (renewal_id) REFERENCES renewals(id)
);
CREATE TABLE overdue_follow_up_states (
 case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 revision BIGINT NOT NULL, version BIGINT NOT NULL
);
CREATE TABLE overdue_follow_ups (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 case_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 rental_id BINARY(16) NOT NULL, type VARCHAR(24) NOT NULL, content VARCHAR(2000) NOT NULL,
 actor_id BINARY(16) NOT NULL, recorded_at DATETIME(6) NOT NULL, external_ref BINARY(16) NULL,
 policy_ref VARCHAR(200) NULL, policy_version VARCHAR(100) NULL,
 INDEX idx_overdue_follow_up_timeline (case_ref,recorded_at),
 FOREIGN KEY (rental_id) REFERENCES rentals(id)
);
