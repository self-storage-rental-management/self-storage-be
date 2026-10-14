package com.storagehub.api.facility;

import com.storagehub.domain.model.UnitTypeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UnitTypeResponse(
    UUID id,
    UUID facilityId,
    String code,
    String name,
    String description,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal areaM2,
    BigDecimal volumeM3,
    BigDecimal monthlyPrice,
    BigDecimal securityDepositAmount,
    String imageUrl,
    BigDecimal maxLoadKg,
    int rackCount,
    BigDecimal rackLengthM,
    BigDecimal rackWidthM,
    BigDecimal rackHeightM,
    BigDecimal aisleWidthM,
    String trolleyType,
    Integer smallBoxCapacity,
    Integer largeBoxCapacity,
    UnitTypeStatus status,
    long totalCount,
    long availableCount,
    long occupiedCount,
    Instant createdAt,
    Instant updatedAt
) {
}
