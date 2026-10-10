package com.storagehub.api.support;

import com.storagehub.domain.model.SupportTicketStatus;
import com.storagehub.service.support.SupportSources.Sla;
import java.time.Instant;
import java.util.*;

/** No entities or internal messages are serialized. Timelines are paginated separately. */
public record SupportResponse(UUID id,UUID customerId,UUID facilityId,UUID assignedStaffId,SupportTicketStatus status,
    String subject,String description,Instant createdAt,Long version,Long assignmentRevision,
    Instant assignedAt,Instant acceptedAt,Instant resolvedAt,Instant closedAt,UUID resolvedBy,
    UUID parentTicketId,String linkedType,UUID linkedId,boolean workflowReady,
    String slaCompleteness,Sla sla,String missingSourceReason) {
    public record Message(UUID id,UUID ticketId,UUID authorId,String authorRole,String visibility,String body,
        Instant sentAt,String evidenceCompleteness,List<UUID> evidenceFileIds) {}
    public record Event(UUID id,String type,UUID actorId,UUID assignedStaffId,long assignmentRevision,String reason,Instant recordedAt) {}
    public record Escalation(UUID id,UUID ticketId,String targetModule,String status,String reason,String decisionReason,
        UUID receiverRef,String receiverStatus,UUID resultRef,String resultCompleteness,Instant requestedAt,Instant decidedAt) {}
    public record StaffOption(UUID id,String fullName) {}
}
