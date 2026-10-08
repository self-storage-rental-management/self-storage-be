package com.storagehub.service.support;

import com.storagehub.domain.model.SupportTicket;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Additive metadata only. Existing SupportTicket and status enum stay unchanged. */
@Entity @Table(name="support_workflow_states") @Getter
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class SupportState {
    @Id private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="id") private SupportTicket ticket;
    @Version private long version;
    private long assignmentRevision;
    private UUID acceptedBy;
    private Instant assignedAt;
    private Instant acceptedAt;
    private UUID resolvedBy;
    private Instant resolvedAt;
    private Instant closedAt;
    private Instant lastPublicStaffReplyAt;
    private Instant firstPublicStaffReplyAt;
    private UUID parentTicketId;
    @Column(length=32) private String linkedType;
    private UUID linkedId;
    private Instant changedAt;
    private long changeSequence;
    public SupportState(SupportTicket ticket, String linkedType, UUID linkedId, UUID parent, Instant now) {
        this.ticket=ticket;this.linkedType=linkedType;this.linkedId=linkedId;parentTicketId=parent;changedAt=now;
    }
    public void touch(Instant now) { changedAt=now;changeSequence++; }
    public void assign(Instant now) {assignmentRevision++;assignedAt=now;acceptedAt=null;acceptedBy=null;touch(now);}
    public void accept(UUID staff, Instant now) {acceptedBy=staff;acceptedAt=now;touch(now);}
    public void reply(Instant now) {if(firstPublicStaffReplyAt==null)firstPublicStaffReplyAt=now;lastPublicStaffReplyAt=now;touch(now);}
    public void resolve(UUID staff, Instant now) {resolvedBy=staff;resolvedAt=now;closedAt=null;touch(now);}
    public void close(Instant now) {closedAt=now;touch(now);}
    public void reopen(boolean keepStaff, Instant now) {resolvedBy=null;resolvedAt=null;closedAt=null;if(!keepStaff){acceptedAt=null;acceptedBy=null;assignmentRevision++;}touch(now);}
}
