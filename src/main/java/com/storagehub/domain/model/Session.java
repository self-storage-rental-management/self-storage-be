package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
public class Session extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Filled in the same transaction after the session id is available for JWT issuance.
    @Column(unique = true, length = 128)
    private String tokenHash;

    @Column(unique = true, length = 128)
    private String refreshTokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant refreshExpiresAt;

    @Column
    private Instant revokedAt;

    @Column(length = 64)
    private String createdIp;

    @Column(length = 512)
    private String userAgent;

    @Column
    private Instant lastSeenAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
