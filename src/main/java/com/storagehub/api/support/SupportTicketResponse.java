package com.storagehub.api.support;

import com.storagehub.domain.model.SupportTicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SupportTicketResponse(
    UUID id,
    UUID customerId,
    String customerName,
    String customerEmail,
    UUID facilityId,
    String facilityName,
    UUID assignedToId,
    String assignedToName,
    SupportTicketStatus status,
    String subject,
    String description,
    List<Message> messages,
    Instant createdAt,
    Instant updatedAt
) {
    public record Message(
        UUID id,
        UUID authorId,
        String authorName,
        boolean customerMessage,
        String body,
        Instant createdAt
    ) {}
}
