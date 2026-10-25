CREATE TABLE customer_chat_sessions (
    customer_id BINARY(16) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    messages_json LONGTEXT NOT NULL,
    ticket_id BINARY(16) NULL,
    auto_return BOOLEAN NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    mutation_id BINARY(16) NULL,
    mutation_hash VARCHAR(64) NULL,
    CONSTRAINT fk_customer_chat_session_user FOREIGN KEY (customer_id) REFERENCES users(id)
);
CREATE INDEX ix_customer_chat_session_expiry ON customer_chat_sessions(expires_at);
