package com.storagehub.api.reporting;

import java.time.Instant;
import java.util.UUID;

public record FacilityActivityResponse(
    UUID id,
    String action,
    String actionLabel,
    String entityType,
    UUID entityId,
    UUID actorId,
    String actorName,
    String actorRole,
    UUID facilityId,
    String facilityName,
    String notes,
    String beforeStateJson,
    String afterStateJson,
    String correlationId,
    Instant timestamp
) {}
