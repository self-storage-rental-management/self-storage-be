package com.storagehub.service.renewal.persistence;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="renewal_accepted_revisions",uniqueConstraints={
    @UniqueConstraint(name="uk_renewal_revision",columnNames={"renewal_id","revision_number"}),
    @UniqueConstraint(name="uk_renewal_accepted_quote",columnNames="quote_id")})
@Getter @org.hibernate.annotations.Immutable @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalAcceptedRevision extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="renewal_id",nullable=false,updatable=false)
    private Renewal renewal;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="quote_id",nullable=false,updatable=false)
    private RenewalQuote quote;
    @Column(name="revision_number",nullable=false,updatable=false) private int revisionNumber;
    @Column(nullable=false,updatable=false) private Instant acceptedAt;
    @Lob @Column(updatable=false) private String note;
    public RenewalAcceptedRevision(Renewal renewal,RenewalQuote quote,int revision,Instant acceptedAt,String note) {
        if(renewal==null||quote==null||revision<1||acceptedAt==null)throw new IllegalArgumentException("Accepted revision requires persisted sources");
        this.renewal=renewal;this.quote=quote;this.revisionNumber=revision;this.acceptedAt=acceptedAt;this.note=note;
    }
}
