package com.storagehub.service.overdue;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Revision of this module's append-only coordination stream, not a version of shared finance. */
@Entity @Table(name="overdue_follow_up_states") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class OverdueFollowUpState {
    @Id @Column(length=120) private String caseRef;
    @Column(nullable=false) private long revision;
    @Version private long version;
    public OverdueFollowUpState(String ref){caseRef=ref;}
    public void appended(){revision++;}
}
