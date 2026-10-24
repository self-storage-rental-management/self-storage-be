CREATE TABLE support_message_reads (
    reader_id BINARY(16) NOT NULL,
    message_id BINARY(16) NOT NULL,
    ticket_id BINARY(16) NOT NULL,
    read_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (reader_id, message_id),
    CONSTRAINT fk_support_read_user FOREIGN KEY (reader_id) REFERENCES users(id),
    CONSTRAINT fk_support_read_message FOREIGN KEY (message_id) REFERENCES support_messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_read_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE
);
CREATE INDEX ix_support_read_ticket ON support_message_reads(ticket_id,reader_id);
