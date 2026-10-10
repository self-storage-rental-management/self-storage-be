package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PublicFacilityResponse(
    UUID id,
    String code,
    String name,
    String address,
    String city,
    FacilityStatus status,
    long totalUnits,
    long availableUnits,
    BigDecimal startingMonthlyPrice,
    Instant createdAt,
    Instant updatedAt
) {
}
