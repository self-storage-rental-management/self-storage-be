package com.storagehub.api.admin;

import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AdminPatchUserRequest(
    @Email @Size(max = 320) String email,
    @Size(max = 160) String fullName,
    @Size(max = 30) String phone,
    Set<RoleCode> roles,
    Map<UUID, FacilityScopeLevel> facilityScopes
) {
    public boolean isEmpty() {
        return email == null && fullName == null && phone == null && roles == null && facilityScopes == null;
    }
}
