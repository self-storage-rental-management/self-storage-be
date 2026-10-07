package com.storagehub.api.maintenance;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignStaffTaskRequest(
    @NotNull(message = "assignedStaffId is required")
    UUID assignedStaffId
) {}
