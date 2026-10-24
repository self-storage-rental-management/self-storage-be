package com.storagehub.api.unitassignment;

import com.storagehub.domain.model.StorageUnitStatus;
import java.util.UUID;
import java.math.BigDecimal;

public record AssignableUnitResponse(
    UUID storageUnitId,
    String storageUnitCode,
    String floor,
    String zone,
    StorageUnitStatus status,
    String unitTypeName,
    BigDecimal lengthM,
    BigDecimal widthM,
    BigDecimal heightM,
    BigDecimal areaM2,
    BigDecimal volumeM3,
    BigDecimal maxLoadKg
) {
}
