package com.storagehub.service.support;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="support_command_receipts",uniqueConstraints=@UniqueConstraint(columnNames={"actor_id","operation","key_hash"}))
@Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class SupportCommandReceipt {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="actor_id",nullable=false) private UUID actorId;
    @Column(nullable=false,length=32) private String operation;
    @Column(name="key_hash",nullable=false,length=64) private String keyHash;
    @Column(nullable=false,length=64) private String payloadHash;
    @Column(nullable=false,columnDefinition="text") private String resultJson;
    public SupportCommandReceipt(UUID actor,String operation,String keyHash,String payloadHash,String result) {
        actorId=actor;this.operation=operation;this.keyHash=keyHash;this.payloadHash=payloadHash;resultJson=result;
    }
}
