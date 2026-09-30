package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record AdminUpdateRolesRequest(@NotEmpty Set<@NotNull RoleCode> roles) {
}
