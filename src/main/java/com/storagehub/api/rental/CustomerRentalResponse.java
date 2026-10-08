package com.storagehub.api.rental;

import com.storagehub.domain.model.RentalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerRentalResponse(
    UUID id,
    UUID reservationId,
    UUID facilityId,
    String facilityName,
    String facilityAddress,
    UUID storageUnitId,
    String storageUnitCode,
    RentalStatus status,
    LocalDate startDate,
    LocalDate contractEndDate,
    BigDecimal monthlyPrice,
    Instant actualReturnedAt,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt
) {}
