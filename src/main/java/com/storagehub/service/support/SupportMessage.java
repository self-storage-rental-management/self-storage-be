package com.storagehub.service.support;

import com.storagehub.domain.model.BaseEntity;
import com.storagehub.domain.model.SupportTicket;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="support_messages") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class SupportMessage extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ticket_id",nullable=false) private SupportTicket ticket;
    @Column(nullable=false) private UUID authorId;
    @Column(nullable=false,length=16) private String authorRole;
    @Column(nullable=false,length=16) private String visibility;
    @Column(nullable=false,length=4000) private String body;
    @Column(nullable=false,length=500) private String evidenceJson;
    @Column(nullable=false) private Instant sentAt;
    public SupportMessage(SupportTicket ticket, UUID author, String role, String visibility, String body, String evidence, Instant now) {
        this.ticket=ticket;authorId=author;authorRole=role;this.visibility=visibility;this.body=body;evidenceJson=evidence;sentAt=now;
    }
}
