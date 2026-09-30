package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AdminActivityLogResponse(
    UUID id,
    UUID actorId,
    String actorName,
    String actorEmail,
    Set<RoleCode> actorRoles,
    UUID facilityId,
    String action,
    String entityType,
    UUID entityId,
    String beforeState,
    String afterState,
    String correlationId,
    Instant createdAt
) {
}
