-- REVIEW DRAFT ONLY. Not a Flyway migration; do not run against shared DB before owner review.
-- MySQL 8 / Hibernate UUID binary(16). Additive tables only; no seeds/backfill or changes to shared tables.
CREATE TABLE renewal_quotes (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 rental_id BINARY(16) NOT NULL, customer_id BINARY(16) NOT NULL,
 terms_json LONGTEXT NOT NULL, terms_hash VARCHAR(64) NOT NULL,
 quoted_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 FOREIGN KEY (rental_id) REFERENCES rentals(id), FOREIGN KEY (customer_id) REFERENCES users(id)
);
CREATE TABLE renewal_accepted_revisions (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 renewal_id BINARY(16) NOT NULL, quote_id BINARY(16) NOT NULL,
 revision_number INT NOT NULL, accepted_at DATETIME(6) NOT NULL, note LONGTEXT NULL,
 CONSTRAINT uk_renewal_revision UNIQUE (renewal_id, revision_number),
 CONSTRAINT uk_renewal_accepted_quote UNIQUE (quote_id),
 FOREIGN KEY (renewal_id) REFERENCES renewals(id), FOREIGN KEY (quote_id) REFERENCES renewal_quotes(id)
);
CREATE TABLE renewal_workflows (
 id BINARY(16) PRIMARY KEY, version BIGINT NOT NULL,
 accepted_revision_id BINARY(16) NOT NULL, reviewer_id BINARY(16) NULL,
 reviewed_at DATETIME(6) NULL, review_reason VARCHAR(2000) NULL, cancellation_reason VARCHAR(2000) NULL,
 payment_deadline DATETIME(6) NULL, extension_hold_ref BINARY(16) NULL,
 FOREIGN KEY (id) REFERENCES renewals(id),
 FOREIGN KEY (accepted_revision_id) REFERENCES renewal_accepted_revisions(id),
 FOREIGN KEY (reviewer_id) REFERENCES users(id)
);
CREATE TABLE renewal_open_slots (
 id BINARY(16) PRIMARY KEY, renewal_id BINARY(16) NOT NULL UNIQUE,
 FOREIGN KEY (id) REFERENCES rentals(id), FOREIGN KEY (renewal_id) REFERENCES renewals(id)
);
CREATE TABLE renewal_idempotency (
 id BINARY(16) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 actor_id BINARY(16) NOT NULL, operation VARCHAR(32) NOT NULL, request_key VARCHAR(100) NOT NULL, request_key_hash VARCHAR(64) NOT NULL,
 resource_id BINARY(16) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 http_status INT NOT NULL, result_json LONGTEXT NOT NULL,
 CONSTRAINT uk_renewal_idempotency UNIQUE (actor_id,operation,request_key_hash),
 FOREIGN KEY (actor_id) REFERENCES users(id)
);
