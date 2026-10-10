package com.storagehub.api.facility;

import java.math.BigDecimal;
import java.util.UUID;

public record PublicUnitTypeResponse(
    UUID id,
    UUID facilityId,
    String code,
    String name,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal areaM2,
    BigDecimal volumeM3,
    BigDecimal monthlyPrice,
    BigDecimal maxLoadKg,
    int rackCount,
    BigDecimal rackLengthM,
    BigDecimal rackWidthM,
    BigDecimal rackHeightM,
    long availableCount
) {
}
