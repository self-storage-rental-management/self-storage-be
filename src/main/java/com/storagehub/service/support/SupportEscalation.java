package com.storagehub.service.support;

import com.storagehub.domain.model.BaseEntity;
import com.storagehub.domain.model.SupportTicket;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="support_escalations") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class SupportEscalation extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ticket_id",nullable=false) private SupportTicket ticket;
    @Column(nullable=false,length=32) private String targetModule;
    @Column(nullable=false,length=32) private String status;
    @Column(nullable=false,length=2000) private String reason;
    @Column(length=2000) private String decisionReason;
    private UUID requestedBy;
    private UUID decidedBy;
    private UUID receiverRef;
    private Instant requestedAt;
    private Instant decidedAt;
    @Column(nullable=false,length=500) private String evidenceJson;
    public SupportEscalation(SupportTicket ticket,String module,String reason,UUID staff,String evidence,Instant now) {
        this.ticket=ticket;targetModule=module;status="REQUESTED";this.reason=reason;requestedBy=staff;evidenceJson=evidence;requestedAt=now;
    }
    public void decision(boolean route,String reason,UUID manager,UUID reference,Instant now) {
        status=route?"ROUTED":"REJECTED";decisionReason=reason;decidedBy=manager;receiverRef=reference;decidedAt=now;
    }
}
