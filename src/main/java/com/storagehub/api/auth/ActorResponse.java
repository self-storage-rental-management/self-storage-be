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
    String permanentAddress,
    String emergencyContactName,
    String emergencyContactPhone,
    String avatarUrl,
    UserStatus status,
    Set<RoleCode> roles,
    Map<UUID, FacilityScopeLevel> facilityScopes,
    Map<UUID, String> facilityNames,
    boolean mustChangePassword,
    Set<String> permissions
) {
}
