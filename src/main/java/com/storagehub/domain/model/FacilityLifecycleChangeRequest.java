package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A Manager request that must be reviewed by Business Operations. */
@Entity
@Table(name = "facility_lifecycle_change_requests")
@Getter
@Setter
@NoArgsConstructor
public class FacilityLifecycleChangeRequest extends BaseEntity {
    @Column(nullable = false)
    private UUID facilityId;

    @Column(nullable = false)
    private String facilityName;

    @Column(nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private String requesterName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FacilityStatus requestedStatus;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    private UUID reviewedBy;
    private Instant reviewedAt;

    @Column(length = 1000)
    private String rejectionReason;
}
