package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "unit_type_change_requests")
@Getter
@Setter
public class UnitTypeChangeRequest extends BaseEntity {
    @Column(nullable = false) private UUID unitTypeId;
    @Column(nullable = false) private UUID facilityId;
    @Column(nullable = false) private UUID requestedBy;
    @Column(nullable = false) private String requesterName;
    @Column(nullable = false) private String facilityName;
    @Column(nullable = false) private String unitTypeCode;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String beforeJson;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String afterJson;
    @Column(nullable = false) private String status = "PENDING";
    private UUID reviewedBy;
    private Instant reviewedAt;
    @Column(length = 500) private String rejectionReason;
    @Column(nullable = false) private boolean requiresRepricing;
}
