package com.storagehub.api.unitassignment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateUnitAssignmentRequest(
    @NotNull UUID storageUnitId
) {
}
