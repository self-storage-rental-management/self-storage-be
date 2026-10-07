package com.storagehub.api.maintenance;

import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record CreateMaintenanceTaskRequest(
    @NotNull(message = "storageUnitId is required")
    UUID storageUnitId,

    @NotBlank(message = "title is required")
    @Size(max = 255, message = "title cannot exceed 255 characters")
    String title,

    @NotBlank(message = "reason is required")
    @Size(max = 2000, message = "reason cannot exceed 2000 characters")
    String reason,

    TaskPriority priority,

    DamageClassification damageClassification,

    UUID assignedStaffId,

    LocalDate dueAt,

    UUID returnCaseId
) {}
