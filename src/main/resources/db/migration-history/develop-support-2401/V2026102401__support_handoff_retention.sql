ALTER TABLE support_workflow_states
    ADD COLUMN conversation_expires_at TIMESTAMP(6) NULL,
    ADD COLUMN conversation_purged_at TIMESTAMP(6) NULL;
CREATE INDEX ix_support_conversation_expiry ON support_workflow_states(conversation_purged_at, conversation_expires_at);

-- Only conversations handed over from AI opt into the ten-day retention rule.
UPDATE support_workflow_states s
JOIN (SELECT ticket_id, MAX(sent_at) last_message_at FROM support_messages GROUP BY ticket_id) m ON m.ticket_id=s.id
SET s.conversation_expires_at=DATE_ADD(m.last_message_at, INTERVAL 10 DAY)
WHERE EXISTS (SELECT 1 FROM support_messages c WHERE c.ticket_id=s.id AND c.author_role IN ('AI_CONTEXT','USER_CONTEXT'));
