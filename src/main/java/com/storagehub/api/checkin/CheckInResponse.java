package com.storagehub.api.checkin;

import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;

public record CheckInResponse(
    UUID checkInId,
    CheckInStatus checkInStatus,
    Instant scheduledAt,
    Instant checkedInAt,
    String readinessNote,
    String rejectionReason,
    UnitReleaseDisposition rejectionDisposition,
    UUID performedBy,
    String performedByName,
    UUID reservationId,
    String reservationCode,
    ReservationStatus reservationStatus,
    UUID customerId,
    String customerName,
    String customerEmail,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String storageUnitCode,
    StorageUnitStatus storageUnitStatus,
    UUID assignmentId,
    UnitAssignmentStatus assignmentStatus,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal declaredGoodsWeightKg,
    BigDecimal declaredGoodsVolumeM3,
    CompleteCheckInRequest handover
) {
}
