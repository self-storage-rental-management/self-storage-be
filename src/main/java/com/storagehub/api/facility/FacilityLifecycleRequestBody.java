package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FacilityLifecycleRequestBody(
    @NotNull FacilityStatus requestedStatus,
    @Size(max = 1000) String reason
) {}
