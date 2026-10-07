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
    name = "unit_assignments",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_unit_assignment_cancel_actor_key",
        columnNames = {"cancelled_by", "cancellation_idempotency_key"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class UnitAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UnitAssignmentStatus status = UnitAssignmentStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @Column(nullable = false)
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column
    private Instant cancelledAt;

    @Column(length = 1000)
    private String cancelReason;

    @Column(name = "cancellation_idempotency_key", length = 100)
    private String cancellationIdempotencyKey;

    @Column(length = 64)
    private String cancellationRequestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UnitReleaseDisposition releaseDisposition;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private StorageUnitStatus previousStorageUnitStatus;

    @Version
    private long version;
}
