package com.storagehub.api.maintenance;

import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.TaskPriority;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MaintenanceTaskResponse(
    UUID id,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String unitCode,
    UUID returnCaseId,
    String title,
    String reason,
    TaskPriority priority,
    DamageClassification damageClassification,
    UUID reportedById,
    String reportedByName,
    UUID assignedStaffId,
    String assignedStaffName,
    MaintenanceTaskStatus status,
    LocalDate dueAt,
    Instant startedAt,
    Instant completedAt,
    String resultReport,
    List<String> evidencePhotos,
    Instant createdAt,
    Instant updatedAt
) {}
