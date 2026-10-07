package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.maintenance.AssignStaffTaskRequest;
import com.storagehub.api.maintenance.CompleteMaintenanceTaskRequest;
import com.storagehub.api.maintenance.CreateMaintenanceTaskRequest;
import com.storagehub.api.maintenance.MaintenanceTaskResponse;
import com.storagehub.api.maintenance.UpdateStorageUnitStatusRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final MaintenanceTaskRepository maintenanceTaskRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MaintenanceTaskResponse createTask(ActorPrincipal actor, CreateMaintenanceTaskRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_INVENTORY);
        StorageUnit unit = storageUnitRepository.findById(request.storageUnitId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));

        facilityScopeService.assertCanManage(actor, unit.getFacility().getId());

        User reportedBy = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reporter user not found"));

        User assignedStaff = null;
        if (request.assignedStaffId() != null) {
            assignedStaff = userRepository.findById(request.assignedStaffId())
                .orElseThrow(() -> ApiExceptions.notFound("Assigned staff was not found"));
        }

        ReturnCase returnCase = null;
        if (request.returnCaseId() != null) {
            returnCase = returnCaseRepository.findById(request.returnCaseId()).orElse(null);
        }

        MaintenanceTask task = new MaintenanceTask();
        task.setFacility(unit.getFacility());
        task.setStorageUnit(unit);
        task.setReturnCase(returnCase);
        task.setTitle(request.title().trim());
        task.setReason(request.reason().trim());
        task.setPriority(request.priority() != null ? request.priority() : TaskPriority.medium);
        task.setDamageClassification(request.damageClassification());
        task.setReportedBy(reportedBy);
        task.setAssignedTo(assignedStaff);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(request.dueAt());

        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        // Put storage unit in maintenance status
        if (unit.getStatus() != StorageUnitStatus.maintenance) {
            unit.setStatus(StorageUnitStatus.maintenance);
            storageUnitRepository.save(unit);
        }

        auditLogService.recordMutation(
            "MAINTENANCE_TASK_CREATED",
            "maintenance_task",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        if (assignedStaff != null) {
            notificationService.createNotification(
                assignedStaff.getId(),
                NotificationType.SYSTEM,
                "Nhiệm vụ bảo trì mới",
                "Bạn được phân công nhiệm vụ bảo trì: " + saved.getTitle() + " tại gian " + unit.getCode(),
                saved.getId()
            );
        }

        return toResponse(saved);
    }

    @Transactional
    public MaintenanceTaskResponse updateUnitStatus(
        ActorPrincipal actor,
        UUID unitId,
        UpdateStorageUnitStatusRequest request
    ) {
        authorizationService.require(actor, SystemPermission.MANAGE_INVENTORY);
        StorageUnit unit = storageUnitRepository.findById(unitId)
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));

        facilityScopeService.assertCanManage(actor, unit.getFacility().getId());

        StorageUnitStatus previousStatus = unit.getStatus();
        StorageUnitStatus newStatus = request.status();

        if (newStatus != StorageUnitStatus.available && newStatus != StorageUnitStatus.maintenance) {
            throw ApiExceptions.validation("Can only change status directly to available or maintenance", null);
        }

        if (previousStatus == StorageUnitStatus.occupied && newStatus == StorageUnitStatus.maintenance) {
            throw ApiExceptions.conflict("Cannot place an actively occupied storage unit into maintenance without completing check-out/return first");
        }

        unit.setStatus(newStatus);
        storageUnitRepository.save(unit);

        MaintenanceTask createdOrCompletedTask = null;

        if (newStatus == StorageUnitStatus.maintenance) {
            boolean shouldCreateTask = request.createMaintenanceTask() == null || request.createMaintenanceTask();
            if (shouldCreateTask) {
                User reportedBy = userRepository.findById(actor.userId())
                    .orElseThrow(() -> ApiExceptions.notFound("User not found"));

                User assignedStaff = null;
                if (request.assignedStaffId() != null) {
                    assignedStaff = userRepository.findById(request.assignedStaffId()).orElse(null);
                }

                MaintenanceTask task = new MaintenanceTask();
                task.setFacility(unit.getFacility());
                task.setStorageUnit(unit);
                task.setTitle("Bảo trì gian " + unit.getCode());
                task.setReason(request.reason() != null && !request.reason().isBlank() ? request.reason().trim() : "Kiểm tra và bảo trì định kỳ theo yêu cầu vận hành");
                task.setPriority(request.priority() != null ? request.priority() : TaskPriority.medium);
                task.setDamageClassification(request.damageClassification());
                task.setReportedBy(reportedBy);
                task.setAssignedTo(assignedStaff);
                task.setStatus(MaintenanceTaskStatus.open);
                task.setDueAt(request.dueAt());
                createdOrCompletedTask = maintenanceTaskRepository.save(task);

                if (assignedStaff != null) {
                    notificationService.createNotification(
                        assignedStaff.getId(),
                        NotificationType.SYSTEM,
                        "Phân công bảo trì gian kho",
                        "Bạn được giao bảo trì gian " + unit.getCode(),
                        createdOrCompletedTask.getId()
                    );
                }
            }
        } else if (newStatus == StorageUnitStatus.available) {
            // Automatically complete active maintenance tasks for this unit
            List<MaintenanceTask> openTasks = maintenanceTaskRepository.findByStorageUnit_IdAndStatusIn(
                unitId, List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
            );
            for (MaintenanceTask openTask : openTasks) {
                openTask.setStatus(MaintenanceTaskStatus.completed);
                openTask.setCompletedAt(Instant.now());
                if (openTask.getResultReport() == null || openTask.getResultReport().isBlank()) {
                    openTask.setResultReport(request.reason() != null && !request.reason().isBlank() ? request.reason().trim() : "Đã nghiệm thu, gian kho sẵn sàng khai thác");
                }
                maintenanceTaskRepository.save(openTask);
                createdOrCompletedTask = openTask;
            }
        }

        auditLogService.recordMutation(
            "STORAGE_UNIT_STATUS_UPDATED",
            "storage_unit",
            unit.getId(),
            unit.getFacility().getId(),
            previousStatus,
            newStatus
        );

        return createdOrCompletedTask != null ? toResponse(createdOrCompletedTask) : null;
    }

    @Transactional
    public MaintenanceTaskResponse assignStaff(ActorPrincipal actor, UUID taskId, AssignStaffTaskRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_STAFF_TASKS);
        MaintenanceTask task = maintenanceTaskRepository.findById(taskId)
            .orElseThrow(() -> ApiExceptions.notFound("Maintenance task was not found"));

        facilityScopeService.assertCanManage(actor, task.getFacility().getId());

        User staff = userRepository.findById(request.assignedStaffId())
            .orElseThrow(() -> ApiExceptions.notFound("Staff user was not found"));

        task.setAssignedTo(staff);
        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        notificationService.createNotification(
            staff.getId(),
            NotificationType.SYSTEM,
            "Phân công nhiệm vụ bảo trì",
            "Quản lý đã giao cho bạn nhiệm vụ: " + task.getTitle() + " tại gian " + task.getStorageUnit().getCode(),
            saved.getId()
        );

        auditLogService.recordMutation(
            "MAINTENANCE_TASK_ASSIGNED",
            "maintenance_task",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional
    public MaintenanceTaskResponse startTask(ActorPrincipal actor, UUID taskId) {
        MaintenanceTask task = maintenanceTaskRepository.findById(taskId)
            .orElseThrow(() -> ApiExceptions.notFound("Maintenance task was not found"));

        facilityScopeService.assertCanOperate(actor, task.getFacility().getId());

        if (task.getStatus() == MaintenanceTaskStatus.completed || task.getStatus() == MaintenanceTaskStatus.cancelled) {
            throw ApiExceptions.conflict("Cannot start a task that is already " + task.getStatus());
        }

        task.setStatus(MaintenanceTaskStatus.in_progress);
        task.setStartedAt(Instant.now());

        if (task.getAssignedTo() == null) {
            User staff = userRepository.findById(actor.userId()).orElse(null);
            task.setAssignedTo(staff);
        }

        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        auditLogService.recordMutation(
            "MAINTENANCE_TASK_STARTED",
            "maintenance_task",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional
    public MaintenanceTaskResponse completeTask(
        ActorPrincipal actor,
        UUID taskId,
        CompleteMaintenanceTaskRequest request
    ) {
        MaintenanceTask task = maintenanceTaskRepository.findById(taskId)
            .orElseThrow(() -> ApiExceptions.notFound("Maintenance task was not found"));

        facilityScopeService.assertCanOperate(actor, task.getFacility().getId());

        if (task.getStatus() == MaintenanceTaskStatus.completed) {
            throw ApiExceptions.conflict("Task is already completed");
        }

        task.setStatus(MaintenanceTaskStatus.completed);
        task.setCompletedAt(Instant.now());
        task.setResultReport(request.resultReport() != null ? request.resultReport().trim() : "Đã hoàn thành bảo trì.");

        if (request.evidencePhotos() != null && !request.evidencePhotos().isEmpty()) {
            try {
                task.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (Exception ignored) {
            }
        }

        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        boolean makeAvailable = request.makeUnitAvailable() == null || request.makeUnitAvailable();
        if (makeAvailable) {
            StorageUnit unit = saved.getStorageUnit();
            unit.setStatus(StorageUnitStatus.available);
            storageUnitRepository.save(unit);
        }

        auditLogService.recordMutation(
            "MAINTENANCE_TASK_COMPLETED",
            "maintenance_task",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional
    public MaintenanceTaskResponse cancelTask(ActorPrincipal actor, UUID taskId, String reason) {
        authorizationService.require(actor, SystemPermission.MANAGE_STAFF_TASKS);
        MaintenanceTask task = maintenanceTaskRepository.findById(taskId)
            .orElseThrow(() -> ApiExceptions.notFound("Maintenance task was not found"));

        facilityScopeService.assertCanManage(actor, task.getFacility().getId());

        if (task.getStatus() == MaintenanceTaskStatus.completed) {
            throw ApiExceptions.conflict("Cannot cancel an already completed task");
        }

        task.setStatus(MaintenanceTaskStatus.cancelled);
        if (reason != null && !reason.isBlank()) {
            task.setResultReport("Đã hủy nhiệm vụ: " + reason.trim());
        }

        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        auditLogService.recordMutation(
            "MAINTENANCE_TASK_CANCELLED",
            "maintenance_task",
            saved.getId(),
            saved.getFacility().getId(),
            null,
            toResponse(saved)
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceTaskResponse> listTasks(
        ActorPrincipal actor,
        UUID facilityId,
        UUID storageUnitId,
        MaintenanceTaskStatus status,
        TaskPriority priority,
        UUID assignedStaffId,
        String q,
        int page,
        int pageSize,
        String correlationId
    ) {
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);

        Collection<UUID> allowedFacilityIds = null;
        if (facilityScopeService.isFacilityScoped(actor)) {
            allowedFacilityIds = actor.facilityScopes().keySet();
            if (facilityId != null) {
                facilityScopeService.assertCanRead(actor, facilityId);
            }
        }

        Pageable pageable = pageable(page, pageSize);
        Page<MaintenanceTaskResponse> result = maintenanceTaskRepository.searchTasks(
            facilityId,
            storageUnitId,
            status,
            priority,
            assignedStaffId,
            allowedFacilityIds,
            q != null ? q.trim() : null,
            pageable
        ).map(this::toResponse);

        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public MaintenanceTaskResponse getTask(ActorPrincipal actor, UUID taskId) {
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        MaintenanceTask task = maintenanceTaskRepository.findById(taskId)
            .orElseThrow(() -> ApiExceptions.notFound("Maintenance task was not found"));

        if (facilityScopeService.isFacilityScoped(actor)) {
            facilityScopeService.assertCanRead(actor, task.getFacility().getId());
        }

        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceTaskResponse> getUnitMaintenanceHistory(ActorPrincipal actor, UUID unitId) {
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        StorageUnit unit = storageUnitRepository.findById(unitId)
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));

        if (facilityScopeService.isFacilityScoped(actor)) {
            facilityScopeService.assertCanRead(actor, unit.getFacility().getId());
        }

        return maintenanceTaskRepository.findByStorageUnit_IdOrderByCreatedAtDesc(unitId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public MaintenanceTaskResponse toResponse(MaintenanceTask t) {
        List<String> evidencePhotos = Collections.emptyList();
        if (t.getEvidencePhotosJson() != null && !t.getEvidencePhotosJson().isBlank()) {
            try {
                evidencePhotos = objectMapper.readValue(t.getEvidencePhotosJson(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
            }
        }
        return new MaintenanceTaskResponse(
            t.getId(),
            t.getFacility().getId(),
            t.getFacility().getName(),
            t.getStorageUnit().getId(),
            t.getStorageUnit().getCode(),
            t.getReturnCase() != null ? t.getReturnCase().getId() : null,
            t.getTitle(),
            t.getReason(),
            t.getPriority(),
            t.getDamageClassification(),
            t.getReportedBy() != null ? t.getReportedBy().getId() : null,
            t.getReportedBy() != null ? t.getReportedBy().getFullName() : null,
            t.getAssignedTo() != null ? t.getAssignedTo().getId() : null,
            t.getAssignedTo() != null ? t.getAssignedTo().getFullName() : null,
            t.getStatus(),
            t.getDueAt(),
            t.getStartedAt(),
            t.getCompletedAt(),
            t.getResultReport(),
            evidencePhotos,
            t.getCreatedAt(),
            t.getUpdatedAt()
        );
    }
}
