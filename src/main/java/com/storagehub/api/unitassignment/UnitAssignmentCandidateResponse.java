package com.storagehub.api.unitassignment;

import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UnitAssignmentCandidateResponse(
    UUID reservationId,
    String reservationCode,
    ReservationStatus reservationStatus,
    UUID customerId,
    String customerName,
    String customerEmail,
    UUID facilityId,
    String facilityName,
    UUID unitTypeId,
    String unitTypeName,
    LocalDate startDate,
    LocalDate endDate,
    Instant confirmedAt,
    BigDecimal totalGoodsVolumeM3,
    BigDecimal totalGoodsWeightKg
) {
}
