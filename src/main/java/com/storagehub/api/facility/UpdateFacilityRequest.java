package com.storagehub.api.facility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.storagehub.domain.model.FacilityStatus;

public record UpdateFacilityRequest(
    @NotBlank @Size(max = 160) String name,
    @NotBlank @Size(max = 255) String address,
    @NotBlank @Size(max = 100) String city,
    @Size(max = 32) String phone,
    @Size(max = 120) String accessHours,
    String imageUrl,
    Boolean climateControlled,
    @Size(max = 255) String securityDescription,
    FacilityStatus status
) {}
