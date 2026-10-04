package com.storagehub.api.unitrelease;

import com.storagehub.domain.model.UnitReleaseDisposition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReleaseAssignedUnitRequest(
    @NotNull UUID assignmentId,
    @NotNull UnitReleaseDisposition disposition,
    @NotBlank @Size(max = 1000) String reason
) {
}
