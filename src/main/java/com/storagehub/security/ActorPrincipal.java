package com.storagehub.security;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ActorPrincipal(
    UUID userId,
    UUID sessionId,
    Set<RoleCode> roles,
    Set<String> permissions,
    Map<UUID, FacilityScopeLevel> facilityScopes
) {
    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(RoleCode... requiredRoles) {
        for (RoleCode role : requiredRoles) {
            if (hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPermission(SystemPermission permission) {
        return permissions.contains(permission.code());
    }
}
