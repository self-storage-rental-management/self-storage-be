package com.storagehub.service.support;

import com.storagehub.domain.model.BaseEntity;
import com.storagehub.domain.model.SupportTicket;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Internal history. Never included in Customer DTOs. */
@Entity @Table(name="support_workflow_events") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class SupportEvent extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ticket_id",nullable=false) private SupportTicket ticket;
    @Column(nullable=false,length=32) private String type;
    private UUID actorId;
    private UUID assignedStaffId;
    private long assignmentRevision;
    @Column(nullable=false,length=2000) private String reason;
    private Instant recordedAt;
    public SupportEvent(SupportTicket ticket,String type,UUID actor,long revision,String reason,Instant now) {
        this.ticket=ticket;this.type=type;actorId=actor;assignmentRevision=revision;
        assignedStaffId=ticket.getAssignedTo()==null?null:ticket.getAssignedTo().getId();this.reason=reason;recordedAt=now;
    }
}
