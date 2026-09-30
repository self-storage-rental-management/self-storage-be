package com.storagehub.api.admin;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AdminCreateUserRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(min = 12, max = 128) String password,
    @NotBlank @Size(max = 160) String fullName,
    @Size(max = 30) String phone,
    @NotEmpty Set<@NotNull RoleCode> roles,
    Map<@NotNull UUID, @NotNull FacilityScopeLevel> facilityScopes
) {
}
