package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import java.util.Set;

public record AdminRoleResponse(
    RoleCode code,
    String name,
    Set<String> permissions
) {
}
