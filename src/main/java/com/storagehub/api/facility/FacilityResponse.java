package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import java.time.Instant;
import java.util.UUID;

public record FacilityResponse(
    UUID id,
    String code,
    String name,
    String address,
    String city,
    String timezone,
    FacilityStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
