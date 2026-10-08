CREATE TABLE IF NOT EXISTS support_tickets (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    customer_id BINARY(16) NOT NULL,
    facility_id BINARY(16) NULL,
    assigned_to BINARY(16) NULL,
    status VARCHAR(24) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_tickets_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_support_tickets_facility FOREIGN KEY (facility_id) REFERENCES facilities(id),
    CONSTRAINT fk_support_tickets_assigned_to FOREIGN KEY (assigned_to) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE INDEX idx_support_tickets_customer_status
    ON support_tickets (customer_id, status, updated_at);
CREATE INDEX idx_support_tickets_facility_status
    ON support_tickets (facility_id, status, updated_at);

CREATE TABLE IF NOT EXISTS support_ticket_messages (
    id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id),
    CONSTRAINT fk_support_ticket_messages_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE INDEX idx_support_ticket_messages_ticket_created
    ON support_ticket_messages (ticket_id, created_at);
