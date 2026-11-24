package com.storagehub.api.returns;

import com.storagehub.domain.model.StorageUnitStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManagerInventoryMismatchDecisionRequest(
    @NotBlank(message = "resolutionNote is required")
    @Size(max = 2000, message = "resolutionNote cannot exceed 2000 characters")
    String resolutionNote,

    StorageUnitStatus proposedUnitStatus
) {}
