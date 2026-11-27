package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "processing_leases")
@Getter
@Setter
public class ProcessingLease {
    @Id
    private UUID reservationId;
    private UUID ownerId;
    private UUID sessionId;
    private UUID token;
    private Instant expiresAt;
    private Instant acquiredAt;
    private Instant maxExpiresAt;
    @Column(nullable = false)
    private long revision;
}
