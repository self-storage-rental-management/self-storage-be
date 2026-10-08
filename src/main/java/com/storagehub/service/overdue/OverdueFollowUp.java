package com.storagehub.service.overdue;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="overdue_follow_ups",indexes=@Index(name="idx_overdue_follow_up_timeline",columnList="case_ref,recorded_at"))
@Getter @org.hibernate.annotations.Immutable @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class OverdueFollowUp extends BaseEntity {
    @Column(name="case_ref",nullable=false,updatable=false,length=120) private String caseRef;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false,updatable=false) private Rental rental;
    @Column(nullable=false,updatable=false,length=24) private String type;
    @Column(nullable=false,updatable=false,length=2000) private String content;
    @Column(nullable=false,updatable=false) private UUID actorId;
    @Column(name="recorded_at",nullable=false,updatable=false) private Instant recordedAt;
    @Column(updatable=false) private UUID externalRef;
    @Column(updatable=false,length=200) private String policyRef;
    @Column(updatable=false,length=100) private String policyVersion;
    public OverdueFollowUp(String ref,Rental rental,String type,String content,UUID actor,Instant at,UUID external,String policy,String version){caseRef=ref;this.rental=rental;this.type=type;this.content=content;actorId=actor;recordedAt=at;externalRef=external;policyRef=policy;policyVersion=version;}
}
