package com.storagehub.api.incident;

import com.storagehub.domain.model.IncidentStatus;
import jakarta.validation.constraints.NotNull;

public record DamageIncidentStatusUpdateRequest(
    @NotNull(message = "status is required")
    IncidentStatus status,

    String resolutionNotes
) {}
