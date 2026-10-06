package com.storagehub.api.unitassignment;

import com.storagehub.domain.model.StorageUnitStatus;
import java.util.UUID;

public record AssignableUnitResponse(
    UUID storageUnitId,
    String storageUnitCode,
    String floor,
    String zone,
    StorageUnitStatus status
) {
}
