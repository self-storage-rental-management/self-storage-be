package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import java.util.Set;

public record AdminRoleResponse(
    RoleCode code,
    String name,
    RoleCode parentRole,
    Set<String> permissions,
    Set<String> directPermissions,
    Set<String> inheritedPermissions,
    long userCount
) {
}
