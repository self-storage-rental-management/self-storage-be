CREATE TABLE processing_lease_requests (
    id BINARY(16) PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    requester_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    token BINARY(16) NOT NULL,
    owner_token BINARY(16) NOT NULL,
    target VARCHAR(32) NOT NULL,
    target_id BINARY(16) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    INDEX ix_lease_requests_reservation (reservation_id, requested_at),
    FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    FOREIGN KEY (requester_id) REFERENCES users(id)
);
UPDATE processing_leases SET max_expires_at = NULL;
