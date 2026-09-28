package com.storagehub.api.auth;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UserStatus;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ActorResponse(
    UUID id,
    String email,
    String fullName,
    String phone,
    UserStatus status,
    Set<RoleCode> roles,
    Map<UUID, FacilityScopeLevel> facilityScopes,
    boolean mustChangePassword,
    Set<String> permissions
) {
}
