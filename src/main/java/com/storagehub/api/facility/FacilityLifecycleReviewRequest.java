package com.storagehub.api.facility;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FacilityLifecycleReviewRequest(
    @NotNull Boolean approved,
    @Size(max = 1000) String reason
) {}
