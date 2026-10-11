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

/** A physical or digital credential issued by an operating Staff member. */
@Entity
@Table(name = "access_credentials")
@Getter
@Setter
@NoArgsConstructor
public class AccessCredential extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_id")
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 16)
    private AccessCredentialType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessCredentialStatus status = AccessCredentialStatus.ACTIVE;

    /** A serial/label or masked display value. Never store the raw PIN/code. */
    @Column(name = "reference", nullable = false, length = 160)
    private String reference;

    /** SHA-256 of a sensitive PIN/access code, when one is supplied. */
    @Column(name = "secret_hash", length = 64)
    private String secretHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issued_by", nullable = false)
    private User issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(length = 1000)
    private String note;
}
