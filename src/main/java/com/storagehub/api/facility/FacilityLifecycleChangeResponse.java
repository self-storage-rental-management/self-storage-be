package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import java.time.Instant;
import java.util.UUID;

public record FacilityLifecycleChangeResponse(
    UUID id,
    UUID facilityId,
    String facilityName,
    UUID requestedBy,
    String requesterName,
    Instant createdAt,
    FacilityStatus requestedStatus,
    String reason,
    String status,
    UUID reviewedBy,
    Instant reviewedAt,
    String rejectionReason,
    FacilityLifecycleSummary summary
) {}
