package com.storagehub.api.unitassignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChangeUnitAssignmentRequest(
    @NotNull UUID storageUnitId,
    @NotBlank @Size(max = 1000) String reason
) {
}
