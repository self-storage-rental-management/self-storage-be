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
@Table(name = "login_history")
@Getter
@Setter
@NoArgsConstructor
public class LoginHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 320)
    private String emailAttempted;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(name = "device_fingerprint", length = 64)
    private String deviceFingerprint;

    @Column(length = 200)
    private String failureReason;

    @Column(nullable = false)
    private Instant occurredAt;
}
