-- Opt-in integration migration. NOT in the default classpath:db/migration location.
-- Review with the database owner before adding this location to a shared deployment.
-- Additive only: no ALTER, DROP, seed, balance backfill or permission grants.
-- JDBC uses canonical UUID text. Stored binary projections enforce references to
-- existing BINARY(16) identifiers without changing shared tables or their writers.

CREATE TABLE rental_ledger_accounts (
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    customer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    facility_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    rental_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(rental_id, '-', ''))) STORED,
    customer_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(customer_id, '-', ''))) STORED,
    facility_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(facility_id, '-', ''))) STORED,
    PRIMARY KEY (rental_id),
    CONSTRAINT ck_rla_revision CHECK (revision >= 0),
    CONSTRAINT ck_rla_uuid CHECK (rental_uuid IS NOT NULL AND customer_uuid IS NOT NULL AND facility_uuid IS NOT NULL),
    CONSTRAINT fk_rla_rental FOREIGN KEY (rental_uuid) REFERENCES rentals(id),
    CONSTRAINT fk_rla_customer FOREIGN KEY (customer_uuid) REFERENCES users(id),
    CONSTRAINT fk_rla_facility FOREIGN KEY (facility_uuid) REFERENCES facilities(id)
) ENGINE=InnoDB;

CREATE TABLE rental_ledger_obligations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    renewal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    due_at_ms BIGINT NOT NULL,
    source_ref VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    renewal_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(renewal_id, '-', ''))) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_rlo_source UNIQUE (rental_id, source_ref, kind),
    CONSTRAINT uk_rlo_rental UNIQUE (id, rental_id),
    CONSTRAINT ck_rlo_kind CHECK (kind IN ('RENT','SECURITY_DEPOSIT','RENEWAL_DEPOSIT','RENEWAL_REMAINDER')),
    CONSTRAINT ck_rlo_amount CHECK (amount >= 0 AND amount < 1000000000000000000),
    CONSTRAINT ck_rlo_renewal_uuid CHECK (renewal_id IS NULL OR renewal_uuid IS NOT NULL),
    CONSTRAINT fk_rlo_account FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id),
    CONSTRAINT fk_rlo_renewal FOREIGN KEY (renewal_uuid) REFERENCES renewals(id),
    INDEX idx_rental_ledger_due (rental_id, due_at_ms, id)
) ENGINE=InnoDB;

CREATE TABLE rental_ledger_receipts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    evidence_ref VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    evidence_file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at_ms BIGINT NOT NULL,
    evidence_file_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(evidence_file_id, '-', ''))) STORED,
    actor_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(actor_id, '-', ''))) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_rlr_evidence UNIQUE (evidence_ref),
    CONSTRAINT uk_rlr_rental UNIQUE (id, rental_id),
    CONSTRAINT ck_rlr_method CHECK (method IN ('CASH','VERIFIED_BANK','SIMULATED')),
    CONSTRAINT ck_rlr_amount CHECK (amount > 0 AND amount < 1000000000000000000),
    CONSTRAINT ck_rlr_uuid CHECK (evidence_file_uuid IS NOT NULL AND actor_uuid IS NOT NULL),
    CONSTRAINT fk_rlr_account FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id),
    CONSTRAINT fk_rlr_file FOREIGN KEY (evidence_file_uuid) REFERENCES file_assets(id),
    CONSTRAINT fk_rlr_actor FOREIGN KEY (actor_uuid) REFERENCES users(id),
    INDEX idx_rental_ledger_receipt (rental_id, received_at_ms, id)
) ENGINE=InnoDB;

CREATE TABLE rental_ledger_allocations (
    receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obligation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    PRIMARY KEY (receipt_id, obligation_id),
    CONSTRAINT ck_rla_amount CHECK (amount > 0 AND amount < 1000000000000000000),
    CONSTRAINT fk_rlalloc_receipt FOREIGN KEY (receipt_id, rental_id) REFERENCES rental_ledger_receipts(id, rental_id),
    CONSTRAINT fk_rlalloc_obligation FOREIGN KEY (obligation_id, rental_id) REFERENCES rental_ledger_obligations(id, rental_id),
    INDEX idx_rental_ledger_allocation (obligation_id, rental_id)
) ENGINE=InnoDB;

CREATE TABLE rental_ledger_refunds (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obligation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,0) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_ref VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payout_ref VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rlrefund_decision UNIQUE (decision_ref),
    CONSTRAINT uk_rlrefund_payout UNIQUE (payout_ref),
    CONSTRAINT ck_rlrefund_amount CHECK (amount > 0 AND amount < 1000000000000000000),
    CONSTRAINT ck_rlrefund_status CHECK (status IN ('RESERVED','EXECUTED')),
    CONSTRAINT ck_rlrefund_payout CHECK ((status='RESERVED' AND payout_ref IS NULL) OR (status='EXECUTED' AND payout_ref IS NOT NULL)),
    CONSTRAINT fk_rlrefund_receipt FOREIGN KEY (receipt_id, rental_id) REFERENCES rental_ledger_receipts(id, rental_id),
    CONSTRAINT fk_rlrefund_obligation FOREIGN KEY (obligation_id, rental_id) REFERENCES rental_ledger_obligations(id, rental_id),
    CONSTRAINT fk_rlrefund_allocation FOREIGN KEY (receipt_id, obligation_id) REFERENCES rental_ledger_allocations(receipt_id, obligation_id),
    INDEX idx_rental_ledger_refund (receipt_id, obligation_id, status)
) ENGINE=InnoDB;

CREATE TABLE rental_ledger_commands (
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rental_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(actor_id, '-', ''))) STORED,
    PRIMARY KEY (actor_id, operation, key_hash),
    CONSTRAINT ck_rlc_operation CHECK (operation IN ('CHARGE','RECEIPT','RESERVE_REFUND','EXECUTE_REFUND')),
    CONSTRAINT ck_rlc_actor_uuid CHECK (actor_uuid IS NOT NULL),
    CONSTRAINT fk_rlc_account FOREIGN KEY (rental_id) REFERENCES rental_ledger_accounts(rental_id),
    CONSTRAINT fk_rlc_actor FOREIGN KEY (actor_uuid) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE notification_outbox (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolution_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_ref VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    policy_version VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    content VARCHAR(2000) NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts BIGINT NOT NULL,
    retry_at_ms BIGINT NOT NULL,
    notification_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    acknowledged_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recipient_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(recipient_id, '-', ''))) STORED,
    notification_uuid BINARY(16) GENERATED ALWAYS AS (UNHEX(REPLACE(notification_id, '-', ''))) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_no_request UNIQUE (resource_id, kind, key_hash),
    CONSTRAINT uk_no_notification UNIQUE (notification_id),
    CONSTRAINT ck_no_kind CHECK (kind IN ('OVERDUE','SUPPORT_REVIEW')),
    CONSTRAINT ck_no_status CHECK (status IN ('PENDING','INBOX','ACKNOWLEDGED')),
    CONSTRAINT ck_no_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_no_recipient_uuid CHECK (recipient_uuid IS NOT NULL),
    CONSTRAINT ck_no_notification_uuid CHECK (notification_id IS NULL OR notification_uuid IS NOT NULL),
    CONSTRAINT ck_no_resolution CHECK (kind<>'SUPPORT_REVIEW' OR resolution_event_id IS NOT NULL),
    CONSTRAINT ck_no_delivery CHECK ((status='PENDING' AND notification_id IS NULL AND acknowledged_at IS NULL) OR (status='INBOX' AND notification_id IS NOT NULL AND acknowledged_at IS NULL) OR (status='ACKNOWLEDGED' AND notification_id IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CONSTRAINT fk_no_recipient FOREIGN KEY (recipient_uuid) REFERENCES users(id),
    CONSTRAINT fk_no_notification FOREIGN KEY (notification_uuid) REFERENCES notifications(id),
    INDEX idx_notification_outbox_dispatch (status, retry_at_ms, id),
    INDEX idx_notification_outbox_review (resource_id, kind, resolution_event_id, status)
) ENGINE=InnoDB;
