CREATE TABLE support_hidden_history (
    ticket_id BINARY(16) NOT NULL PRIMARY KEY,
    customer_id BINARY(16) NOT NULL,
    hidden_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_support_hidden_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_hidden_customer FOREIGN KEY (customer_id) REFERENCES users(id)
);
CREATE INDEX ix_support_hidden_customer ON support_hidden_history(customer_id,ticket_id);
