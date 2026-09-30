package com.storagehub.api.admin;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AdminUserResponse(
    UUID id,
    String fullName,
    String email,
    String phone,
    UserStatus status,
    Set<RoleCode> roles,
    Map<UUID, FacilityScopeLevel> facilityScopes,
    boolean mustChangePassword,
    Instant createdAt,
    Instant updatedAt
) {
}
