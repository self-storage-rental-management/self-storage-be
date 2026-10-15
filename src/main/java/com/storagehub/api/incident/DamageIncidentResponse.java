package com.storagehub.api.incident;

import com.storagehub.domain.model.DamageCause;
import com.storagehub.domain.model.IncidentSeverity;
import com.storagehub.domain.model.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record DamageIncidentResponse(
    UUID id,
    String incidentCode,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String storageUnitCode,
    DamageCause cause,
    IncidentSeverity severity,
    String description,
    Instant occurredAt,
    Instant reportedAt,
    UUID reportedById,
    String reportedByName,
    IncidentStatus status,
    String resolutionNotes,
    String evidencePhotosJson,
    Instant createdAt,
    Instant updatedAt
) {}
