package com.storagehub.api.facility;

import com.storagehub.domain.model.UnitTypeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UnitTypeResponse(
    UUID id,
    UUID facilityId,
    String name,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal areaM2,
    BigDecimal volumeM3,
    BigDecimal pricePerM3,
    BigDecimal monthlyPrice,
    BigDecimal maxLoadKg,
    UnitTypeStatus status,
    long availableUnitCount,
    Instant createdAt,
    Instant updatedAt
) {
}
