package com.storagehub.api.maintenance;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.security.ActorContext;
import com.storagehub.service.MaintenanceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager")
@RequiredArgsConstructor
public class ManagerMaintenanceController {

    private final ActorContext actorContext;
    private final MaintenanceService maintenanceService;

    @PatchMapping("/storage-units/{unitId}/status")
    public ApiResponse<MaintenanceTaskResponse> updateUnitStatus(
        @PathVariable UUID unitId,
        @Valid @RequestBody UpdateStorageUnitStatusRequest request
    ) {
        return new ApiResponse<>(
            maintenanceService.updateUnitStatus(actorContext.required(), unitId, request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/storage-units/{unitId}/maintenance-history")
    public ApiResponse<List<MaintenanceTaskResponse>> getUnitMaintenanceHistory(@PathVariable UUID unitId) {
        return new ApiResponse<>(
            maintenanceService.getUnitMaintenanceHistory(actorContext.required(), unitId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/maintenance-tasks")
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

    @GetMapping("/maintenance-tasks/{taskId}")
    public ApiResponse<MaintenanceTaskResponse> getTask(@PathVariable UUID taskId) {
        return new ApiResponse<>(
            maintenanceService.getTask(actorContext.required(), taskId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/maintenance-tasks")
    public ApiResponse<MaintenanceTaskResponse> createTask(@Valid @RequestBody CreateMaintenanceTaskRequest request) {
        return new ApiResponse<>(
            maintenanceService.createTask(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PatchMapping("/maintenance-tasks/{taskId}/assign")
    public ApiResponse<MaintenanceTaskResponse> assignStaff(
        @PathVariable UUID taskId,
        @Valid @RequestBody AssignStaffTaskRequest request
    ) {
        return new ApiResponse<>(
            maintenanceService.assignStaff(actorContext.required(), taskId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/maintenance-tasks/{taskId}/cancel")
    public ApiResponse<MaintenanceTaskResponse> cancelTask(
        @PathVariable UUID taskId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body != null ? body.get("reason") : null;
        return new ApiResponse<>(
            maintenanceService.cancelTask(actorContext.required(), taskId, reason),
            CorrelationIdContext.current()
        );
    }
}
