package com.storagehub.api.unitrelease;

import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import java.time.Instant;
import java.util.UUID;

public record UnitReleaseResponse(
    UUID reservationId,
    ReservationStatus reservationStatus,
    UUID assignmentId,
    UnitAssignmentStatus assignmentStatus,
    Instant assignmentCancelledAt,
    UUID storageUnitId,
    String storageUnitCode,
    StorageUnitStatus previousStorageUnitStatus,
    StorageUnitStatus storageUnitStatus,
    UnitReleaseDisposition disposition,
    UUID processedBy,
    Instant processedAt
) {
}
