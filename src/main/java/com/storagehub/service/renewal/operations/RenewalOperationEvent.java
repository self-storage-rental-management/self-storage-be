package com.storagehub.service.renewal.operations;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="renewal_operation_events",indexes=@Index(name="idx_renewal_event_timeline",columnList="renewal_id,kind,occurred_at"))
@Getter @org.hibernate.annotations.Immutable @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalOperationEvent extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="renewal_id",nullable=false,updatable=false) private Renewal renewal;
    @Column(nullable=false,updatable=false,length=32) private String kind;
    @Column(nullable=false,updatable=false) private Instant occurredAt;
    @Column(updatable=false) private UUID actorId;
    @Lob @Column(columnDefinition="LONGTEXT",nullable=false,updatable=false) private String payloadJson;
    public RenewalOperationEvent(Renewal n,String kind,Instant at,UUID actor,String json) {
        renewal=n;this.kind=kind;occurredAt=at;actorId=actor;payloadJson=json;
    }
}
