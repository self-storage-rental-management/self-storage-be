package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record FacilityResponse(
    UUID id,
    String code,
    String name,
    String address,
    String city,
    String phone,
    String accessHours,
    String imageUrl,
    boolean climateControlled,
    String securityDescription,
    List<String> managerNames,
    FacilityStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
