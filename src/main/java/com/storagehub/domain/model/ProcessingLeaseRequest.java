package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "processing_lease_requests") @Getter @Setter
public class ProcessingLeaseRequest {
    @Id private UUID id;
    private UUID reservationId;
    private UUID requesterId;
    private UUID sessionId;
    private UUID token;
    private UUID ownerToken;
    private String target;
    private UUID targetId;
    private Instant requestedAt;
    private Instant expiresAt;
    private String status;
}
