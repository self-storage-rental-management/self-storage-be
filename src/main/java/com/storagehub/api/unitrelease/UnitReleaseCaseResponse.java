package com.storagehub.api.unitrelease;

import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UnitReleaseCaseResponse(
    UUID reservationId,
    String reservationCode,
    ReservationStatus reservationStatus,
    Instant cancelledAt,
    String cancelReason,
    UUID facilityId,
    String facilityName,
    UUID unitTypeId,
    Assignment assignment,
    Eligibility eligibility
) {
    public record Assignment(
        UUID assignmentId,
        UnitAssignmentStatus status,
        Instant assignedAt,
        UUID storageUnitId,
        String storageUnitCode,
        StorageUnitStatus storageUnitStatus
    ) {
    }

    public record Eligibility(
        boolean releasable,
        boolean reservationCancelled,
        boolean activeAssignmentPresent,
        boolean completedCheckInPresent,
        boolean activeRentalPresent,
        List<String> blockingReasons
    ) {
    }
}
