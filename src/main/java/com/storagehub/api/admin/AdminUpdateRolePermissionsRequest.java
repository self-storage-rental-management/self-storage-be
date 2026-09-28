package com.storagehub.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record AdminUpdateRolePermissionsRequest(
    @NotNull Set<@NotBlank String> permissions
) {
}
