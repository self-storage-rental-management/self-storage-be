package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "check_ins",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_check_in_reservation",
        columnNames = "reservation_id"
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CheckIn extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_id")
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false)
    private User performedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CheckInStatus status = CheckInStatus.scheduled;

    @Column
    private Instant scheduledAt;

    @Column
    private Instant checkedInAt;

    @Column
    private Instant identityVerifiedAt;

    @Column(length = 30)
    private String verifiedIdentityDocument;

    @Column(length = 1000)
    private String identityVerificationNote;

    @Column
    private Instant contractSignedAt;

    @Column(length = 36)
    private String contractEvidenceReference;

    @Column
    private Instant paymentCollectedAt;

    @Column
    private Instant setupStartedAt;

    @Column
    private Instant setupDueAt;

    @Column
    private Instant setupCompletedAt;

    @Column
    private Instant inventoryVerifiedAt;

    @Column(length = 160)
    private String lockReference;

    @Column(length = 8000)
    private String checklistJson;

    @Column(length = 1000)
    private String readinessNote;

    @Column(length = 1000)
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UnitReleaseDisposition rejectionDisposition;

    @Version
    private long version;
}
