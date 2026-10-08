package com.storagehub.service.renewal.persistence;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Additive workflow version; legacy Renewal without this row remains UNKNOWN, never version zero. */
@Entity @Table(name="renewal_workflows") @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalWorkflow {
    @Id private UUID id;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @MapsId @JoinColumn(name="id") private Renewal renewal;
    @Version private long version;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false) private RenewalAcceptedRevision acceptedRevision;
    @ManyToOne(fetch=FetchType.LAZY) private User reviewer;
    private Instant reviewedAt;
    @Column(length=2000) private String reviewReason;
    @Column(length=2000) private String cancellationReason;
    private Instant paymentDeadline;
    private UUID extensionHoldRef;
    public RenewalWorkflow(Renewal renewal,RenewalAcceptedRevision revision) {this.renewal=renewal;this.acceptedRevision=revision;}
    public void accept(RenewalAcceptedRevision revision) {this.acceptedRevision=revision;}
    public void cancelled(String reason) {this.cancellationReason=reason;}
    public void reviewed(User reviewer,Instant now,String reason,Instant deadline,UUID hold) {
        this.reviewer=reviewer;this.reviewedAt=now;this.reviewReason=reason;this.paymentDeadline=deadline;this.extensionHoldRef=hold;
    }
}
