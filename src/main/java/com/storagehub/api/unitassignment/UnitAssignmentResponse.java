package com.storagehub.api.unitassignment;

import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import java.time.Instant;
import java.util.UUID;

public record UnitAssignmentResponse(
    UUID assignmentId,
    UnitAssignmentStatus assignmentStatus,
    Instant assignedAt,
    UUID assignedBy,
    UUID reservationId,
    String reservationCode,
    ReservationStatus reservationStatus,
    UUID storageUnitId,
    String storageUnitCode,
    StorageUnitStatus storageUnitStatus
) {
}
