package com.storagehub.api.maintenance;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.security.ActorContext;
import com.storagehub.service.MaintenanceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/maintenance-tasks")
@RequiredArgsConstructor
public class StaffMaintenanceController {

    private final ActorContext actorContext;
    private final MaintenanceService maintenanceService;

    @GetMapping
    public PageResponse<MaintenanceTaskResponse> listTasks(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) UUID storageUnitId,
        @RequestParam(required = false) MaintenanceTaskStatus status,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) UUID assignedStaffId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return maintenanceService.listTasks(
            actorContext.required(), facilityId, storageUnitId, status, priority, assignedStaffId, q, page, pageSize, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{taskId}")
    public ApiResponse<MaintenanceTaskResponse> getTask(@PathVariable UUID taskId) {
        return new ApiResponse<>(
            maintenanceService.getTask(actorContext.required(), taskId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{taskId}/start")
    public ApiResponse<MaintenanceTaskResponse> startTask(@PathVariable UUID taskId) {
        return new ApiResponse<>(
            maintenanceService.startTask(actorContext.required(), taskId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{taskId}/complete")
    public ApiResponse<MaintenanceTaskResponse> completeTask(
        @PathVariable UUID taskId,
        @Valid @RequestBody(required = false) CompleteMaintenanceTaskRequest request
    ) {
        CompleteMaintenanceTaskRequest safeRequest = request != null ? request : new CompleteMaintenanceTaskRequest(null, null, true);
        return new ApiResponse<>(
            maintenanceService.completeTask(actorContext.required(), taskId, safeRequest),
            CorrelationIdContext.current()
        );
    }
}
