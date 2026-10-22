package com.storagehub.api.incident;

import com.storagehub.domain.model.DamageCause;
import com.storagehub.domain.model.IncidentSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record DamageIncidentRequest(
    @NotNull(message = "facilityId is required")
    UUID facilityId,

    UUID storageUnitId,

    @NotNull(message = "cause is required")
    DamageCause cause,

    @NotNull(message = "severity is required")
    IncidentSeverity severity,

    @NotBlank(message = "description is required")
    String description,

    @NotNull(message = "occurredAt is required")
    Instant occurredAt,

    String evidencePhotosJson
) {}
