package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "auth_challenges")
@Getter
@Setter
@NoArgsConstructor
public class AuthChallenge extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthChallengePurpose purpose;

    @Column(nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(nullable = false, length = 128)
    private String otpHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant consumedAt;

    @Column(nullable = false)
    private int failedAttempts;

    public boolean isUsable(Instant now) {
        return consumedAt == null && failedAttempts < 5 && expiresAt.isAfter(now);
    }
}
