package com.storagehub.api.admin;

import com.storagehub.domain.model.FacilityScopeLevel;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record AdminUpdateFacilitiesRequest(
    @NotNull Map<@NotNull UUID, @NotNull FacilityScopeLevel> facilityScopes
) {
}
