package com.storagehub.service.renewal.persistence;

import com.storagehub.domain.model.*;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="renewal_idempotency",uniqueConstraints=@UniqueConstraint(name="uk_renewal_idempotency",columnNames={"actor_id","operation","request_key_hash"}))
@Getter @org.hibernate.annotations.Immutable @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class RenewalIdempotency extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="actor_id",nullable=false,updatable=false) private User actor;
    @Column(nullable=false,updatable=false,length=32) private String operation;
    @Column(name="request_key",nullable=false,updatable=false,length=100) private String requestKey;
    @Column(name="request_key_hash",nullable=false,updatable=false,length=64) private String requestKeyHash;
    @Column(nullable=false,updatable=false) private UUID resourceId;
    @Column(nullable=false,updatable=false,length=64) private String payloadHash;
    @Column(nullable=false,updatable=false) private int httpStatus;
    @Lob @Column(nullable=false,updatable=false) private String resultJson;
    public RenewalIdempotency(User actor,String operation,String key,UUID resourceId,String hash,int status,String resultJson) {
        this.actor=actor;this.operation=operation;this.requestKey=key;this.resourceId=resourceId;this.payloadHash=hash;this.httpStatus=status;this.resultJson=resultJson;
        try{this.requestKeyHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
}
