package com.storagehub.api.maintenance;

import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.TaskPriority;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateStorageUnitStatusRequest(
    @NotNull(message = "status is required")
    StorageUnitStatus status,

    @Size(max = 2000, message = "reason cannot exceed 2000 characters")
    String reason,

    Boolean createMaintenanceTask,

    UUID assignedStaffId,

    TaskPriority priority,

    DamageClassification damageClassification,

    LocalDate dueAt
) {}
