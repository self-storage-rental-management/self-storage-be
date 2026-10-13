-- Local Recovery worker records a system follow-up without impersonating a user.
ALTER TABLE overdue_follow_ups MODIFY actor_id BINARY(16) NULL;
