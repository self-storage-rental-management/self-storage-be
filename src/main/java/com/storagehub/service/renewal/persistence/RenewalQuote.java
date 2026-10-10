package com.storagehub.service.renewal.persistence;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Server-authored immutable terms; never a replacement for ReservationQuote. */
@Entity @Table(name="renewal_quotes") @Getter @org.hibernate.annotations.Immutable
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalQuote extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false,updatable=false)
    private Rental rental;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false,updatable=false)
    private User customer;
    @Lob @Column(columnDefinition="LONGTEXT",nullable=false,updatable=false) private String termsJson;
    @Column(nullable=false,updatable=false,length=64) private String termsHash;
    @Column(nullable=false,updatable=false) private Instant quotedAt;
    @Column(nullable=false,updatable=false) private Instant expiresAt;
    public RenewalQuote(Rental rental,User customer,String termsJson,String termsHash,Instant quotedAt,Instant expiresAt) {
        if(rental==null||customer==null||termsJson==null||termsHash==null||quotedAt==null||expiresAt==null||!quotedAt.isBefore(expiresAt))
            throw new IllegalArgumentException("Complete authoritative quote and positive lifetime required");
        this.rental=rental;this.customer=customer;this.termsJson=termsJson;this.termsHash=termsHash;
        this.quotedAt=quotedAt;this.expiresAt=expiresAt;
    }
}
