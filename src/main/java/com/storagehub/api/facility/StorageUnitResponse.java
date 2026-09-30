package com.storagehub.api.facility;

import com.storagehub.domain.model.StorageUnitStatus;
import java.util.UUID;

public record StorageUnitResponse(
    UUID id,
    UUID facilityId,
    UUID unitTypeId,
    String code,
    String floor,
    String zone,
    StorageUnitStatus status
) {
}
