package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.maintenance.AssignStaffTaskRequest;
import com.storagehub.api.maintenance.CompleteMaintenanceTaskRequest;
import com.storagehub.api.maintenance.CreateMaintenanceTaskRequest;
import com.storagehub.api.maintenance.MaintenanceTaskResponse;
import com.storagehub.api.maintenance.MaintenanceStaffOption;
import com.storagehub.api.maintenance.UpdateStorageUnitStatusRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.MaintenanceTaskType;
import com.storagehub.domain.model.MaintenanceRecoveryType;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import java.time.Instant;
import java.time.LocalDate;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.storagehub.domain.repo.RentalRepository rentalRepository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AccessCredentialService accessCredentialService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.storagehub.domain.repo.FileAssetRepository fileAssetRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.email.CustomerLifecycleEmailService customerEmails;

    private final MaintenanceTaskRepository maintenanceTaskRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ReturnCaseRepository returnCaseRepository;
    private final UserRepository userRepository;
    private final UserFacilityScopeRepository userFacilityScopeRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private static void validateDueDate(LocalDate dueAt) {
        if (dueAt != null && dueAt.isBefore(LocalDate.now(BusinessTime.ZONE))) {
            throw ApiExceptions.validation("Hạn xử lý phải từ hôm nay trở đi.", null);
        }
    }

    @Transactional
    public MaintenanceTaskResponse createTask(ActorPrincipal actor, CreateMaintenanceTaskRequest request) {
        authorizationService.require(actor, SystemPermission.MANAGE_INVENTORY);
        validateDueDate(request.dueAt());
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(request.storageUnitId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));

        facilityScopeService.assertCanManage(actor, unit.getFacility().getId());

        if (unit.getStatus() == StorageUnitStatus.occupied) {
            throw ApiExceptions.conflict("Cannot place an actively occupied storage unit into maintenance without completing check-out/return first");
        }
        if (unit.getStatus() == StorageUnitStatus.reserved
            || unit.getStatus() == StorageUnitStatus.held
            || unit.getStatus() == StorageUnitStatus.assigned
            || unit.getStatus() == StorageUnitStatus.archived) {
            throw ApiExceptions.conflict("Cannot place a reserved, assigned, or archived storage unit into maintenance: " + unit.getStatus());
        }

        User reportedBy = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reporter user not found"));

        User assignedStaff = null;
        if (request.assignedStaffId() != null) {
            authorizationService.require(actor, SystemPermission.MANAGE_STAFF_TASKS);
            assignedStaff = userRepository.findById(request.assignedStaffId())
                .orElseThrow(() -> ApiExceptions.notFound("Assigned staff was not found"));
            assertEligibleStaff(assignedStaff, unit.getFacility().getId());
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
        task.setTaskType(MaintenanceTaskType.maintenance);
        task.setReason(request.reason().trim());
        task.setPriority(request.priority() != null ? request.priority() : TaskPriority.medium);
        task.setDamageClassification(request.damageClassification());
        task.setReportedBy(reportedBy);
        task.setAssignedTo(assignedStaff);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(request.dueAt());

        MaintenanceTask saved = maintenanceTaskRepository.save(task);
        if (task.getTaskType() == MaintenanceTaskType.maintenance) customerEmails.facilityChanged(task.getFacility().getId(), "maintenance:" + saved.getId(),
            "Thông báo kế hoạch bảo trì cơ sở", "Cơ sở đã lập nhiệm vụ bảo trì. Vui lòng xem thông tin lịch làm việc, khu vực ảnh hưởng trên web hoặc liên hệ cơ sở trước khi đến.");

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
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(unitId)
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
        if (previousStatus == StorageUnitStatus.reserved
            || previousStatus == StorageUnitStatus.held
            || previousStatus == StorageUnitStatus.assigned
            || previousStatus == StorageUnitStatus.occupied
            || previousStatus == StorageUnitStatus.archived) {
            throw ApiExceptions.conflict("Cannot change storage unit status directly while unit is " + previousStatus);
        }

        boolean shouldCreateTask = !Boolean.FALSE.equals(request.createMaintenanceTask());
        if (newStatus == StorageUnitStatus.maintenance && shouldCreateTask) {
            validateDueDate(request.dueAt());
        }
        unit.setStatus(newStatus);
        storageUnitRepository.save(unit);

        MaintenanceTask createdOrCompletedTask = null;

        if (newStatus == StorageUnitStatus.maintenance) {
            if (shouldCreateTask) {
                User reportedBy = userRepository.findById(actor.userId())
                    .orElseThrow(() -> ApiExceptions.notFound("User not found"));

                User assignedStaff = null;
                if (request.assignedStaffId() != null) {
                    authorizationService.require(actor, SystemPermission.MANAGE_STAFF_TASKS);
                    assignedStaff = userRepository.findById(request.assignedStaffId())
                        .orElseThrow(() -> ApiExceptions.notFound("Assigned staff was not found"));
                    assertEligibleStaff(assignedStaff, unit.getFacility().getId());
                }

                MaintenanceTask task = new MaintenanceTask();
                task.setFacility(unit.getFacility());
                task.setStorageUnit(unit);
                task.setTitle("Bảo trì gian " + unit.getCode());
                task.setTaskType(MaintenanceTaskType.maintenance);
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
        assertEligibleStaff(staff, task.getFacility().getId());

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

    @Transactional(readOnly = true)
    public List<MaintenanceStaffOption> listEligibleStaff(ActorPrincipal actor, UUID facilityId) {
        if (!actor.hasPermission(SystemPermission.MANAGE_STAFF_TASKS)
            && !actor.hasPermission(SystemPermission.PERFORM_CHECKIN)
            && !actor.hasPermission(SystemPermission.PROCESS_RETURNS)) {
            authorizationService.require(actor, SystemPermission.MANAGE_STAFF_TASKS);
        }
        if (actor.hasRole(RoleCode.STAFF)) {
            facilityScopeService.assertCanOperate(actor, facilityId);
        } else {
            facilityScopeService.assertCanManage(actor, facilityId);
        }
        return userRepository.findEligibleStaff(
                UserStatus.ACTIVE,
                RoleCode.STAFF,
                facilityId,
                List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
            )
            .stream()
            .map(user -> new MaintenanceStaffOption(user.getId(), user.getFullName()))
            .toList();
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
        if (task.getStatus() == MaintenanceTaskStatus.cancelled) {
            throw ApiExceptions.conflict("Cannot complete a cancelled maintenance task");
        }
        if (task.getTaskType() == MaintenanceTaskType.inventory_check) {
            throw ApiExceptions.conflict("Inventory check tasks must be completed from the check-in workflow");
        }
        boolean noShowRecovery = task.getRecoveryType() == MaintenanceRecoveryType.RETURN_NO_SHOW
            || task.getReturnCase() != null
                && task.getReturnCase().getStatus() == com.storagehub.domain.model.ReturnCaseStatus.recovery_required;
        boolean recoveryTask = task.getRecoveryType() != null
            && task.getRecoveryType() != MaintenanceRecoveryType.NONE;
        if (recoveryTask && !actor.hasRole(RoleCode.STAFF))
            throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận hoàn tất thu hồi thực tế.");
        if (noShowRecovery) {
            throw ApiExceptions.conflict("Nhiệm vụ thu hồi khách không đến phải được hoàn tất trong biên bản nghiệm thu trả kho.");
        }
        if (recoveryTask && (request.evidencePhotos() == null || request.evidencePhotos().isEmpty())) {
            throw ApiExceptions.validation("Cần tải bằng chứng kiểm kê và bàn giao trước khi hoàn tất thu hồi hàng.", null);
        }
        if (task.getRecoveryType() == MaintenanceRecoveryType.SETUP_OVERDUE
            || task.getRecoveryType() == MaintenanceRecoveryType.MISMATCH_REMOVAL) {
            if (request.resultReport() == null || request.resultReport().isBlank())
                throw ApiExceptions.validation("Vui lòng nhập biên bản, danh mục hàng và người nhận bàn giao.", null);
            if (fileAssetRepository == null) throw ApiExceptions.conflict("Chưa thể xác minh bằng chứng thu hồi.");
            for (String reference : request.evidencePhotos()) {
                com.storagehub.domain.model.FileAsset file;
                try { file = fileAssetRepository.findById(UUID.fromString(reference)).orElse(null); }
                catch (IllegalArgumentException e) { throw ApiExceptions.validation("Bằng chứng thu hồi không hợp lệ.", null); }
                if (file == null || file.getStatus() != com.storagehub.domain.model.FileAssetStatus.ACTIVE
                    || !"MAINTENANCE_TASK".equals(file.getEntityType()) || !taskId.equals(file.getEntityId()))
                    throw ApiExceptions.validation("Bằng chứng phải thuộc đúng nhiệm vụ thu hồi.", null);
            }
        }
        if (recoveryTask && !Boolean.TRUE.equals(request.unitVacated())) {
            throw ApiExceptions.conflict("Cần xác nhận toàn bộ hàng đã được đưa khỏi gian trước khi hoàn tất thu hồi.");
        }
        if (recoveryTask && (request.recoveryHandoverLocation() == null || request.recoveryHandoverLocation().isBlank())) {
            throw ApiExceptions.validation("Cần ghi nhận khu vực lưu tạm khi hoàn tất thu hồi hàng.", null);
        }
        User recoveryWitness = recoveryTask
            ? requireRecoveryWitness(request.recoveryWitnessStaffId(), task.getFacility().getId(), actor.userId())
            : null;

        task.setStatus(MaintenanceTaskStatus.completed);
        task.setCompletedAt(Instant.now());
        task.setResultReport(request.resultReport() != null ? request.resultReport().trim() : "Đã hoàn thành bảo trì.");

        if (request.evidencePhotos() != null && !request.evidencePhotos().isEmpty()) {
            try {
                task.setEvidencePhotosJson(objectMapper.writeValueAsString(request.evidencePhotos()));
            } catch (Exception ignored) {
            }
        }
        if (recoveryTask) {
            task.setRecoveryHandoverLocation(request.recoveryHandoverLocation().trim());
            task.setRecoveryWitness(recoveryWitness);
        }

        MaintenanceTask saved = maintenanceTaskRepository.save(task);

        boolean makeAvailable = !recoveryTask && (request.makeUnitAvailable() == null || request.makeUnitAvailable());
        if ((saved.getRecoveryType() == MaintenanceRecoveryType.SETUP_OVERDUE
            || saved.getRecoveryType() == MaintenanceRecoveryType.MISMATCH_REMOVAL) && saved.getRental() != null) {
            var rental = saved.getRental();
            if (accessCredentialService != null) accessCredentialService.revokeForRental(actor, rental, "Thu hồi quyền truy cập sau khi xác nhận gian trống");
            rental.setActualReturnedAt(saved.getCompletedAt());
            rental.setInventoryPending(false);
            rental.setSetupExitReviewRequired(true);
            rental.setStatus(com.storagehub.domain.model.RentalStatus.return_requested);
            var existingReturn = returnCaseRepository.findByRental_Id(rental.getId()).stream()
                .filter(rc -> rc.getStatus() != com.storagehub.domain.model.ReturnCaseStatus.completed).findFirst();
            var rc = existingReturn.orElseGet(com.storagehub.domain.model.ReturnCase::new);
            if (rc.getId() == null) {
                rc.setRental(rental); rc.setCustomer(rental.getCustomer()); rc.setFacility(rental.getFacility());
                rc.setRequestedBy(userRepository.findById(actor.userId()).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy nhân viên xử lý.")));
                rc.setStorageUnit(rental.getStorageUnit()); rc.setStatus(com.storagehub.domain.model.ReturnCaseStatus.requested);
                rc.setRequestedAt(saved.getCompletedAt()); rc.setScheduledDate(saved.getCompletedAt().atZone(BusinessTime.ZONE).toLocalDate());
                rc.setReturnType(saved.getRecoveryType() == MaintenanceRecoveryType.MISMATCH_REMOVAL
                    ? com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH : com.storagehub.domain.model.ReturnType.RETURN_IN_SETUP);
                rc.setSetupExitRequested(true); rc.setSetupReturnedAt(saved.getCompletedAt());
                rc.setSettlementMode(com.storagehub.domain.model.SettlementMode.MANUAL_REVIEW);
                // No monetary claim is inferred from the physical recovery. Finance captures verified funds at inspection.
                rc.setDepositAmount(java.math.BigDecimal.ZERO);
                rc.setCustomerNotes("Hồ sơ quyết toán sau thu hồi. Cần nghiệm thu và đối soát trước khi hoàn tiền hoặc thu thêm.");
                returnCaseRepository.saveAndFlush(rc);
            }
            saved.setReturnCase(rc);
            maintenanceTaskRepository.save(saved);
            if (rentalRepository != null) rentalRepository.save(rental);
            notificationService.createFacilityOperationsNotifications(rental.getFacility().getId(), NotificationType.RETURN,
                "Chờ quyết toán sau thu hồi", "Kho đã trống. Vui lòng mở hồ sơ trả kho để nghiệm thu và gửi Manager duyệt bảng kê.", rc.getId());
        }
        StorageUnit unit = saved.getStorageUnit();
        boolean hasOtherOpenTasks = hasOtherActiveMaintenanceTasks(unit.getId(), saved.getId());
        unit.setStatus(makeAvailable && !hasOtherOpenTasks ? StorageUnitStatus.available : StorageUnitStatus.maintenance);
        storageUnitRepository.save(unit);

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
        if (task.getStatus() == MaintenanceTaskStatus.cancelled) {
            throw ApiExceptions.conflict("Task is already cancelled");
        }

        if ((task.getRecoveryType() != null && task.getRecoveryType() != MaintenanceRecoveryType.NONE)
            || task.getReturnCase() != null
                && task.getReturnCase().getStatus() == com.storagehub.domain.model.ReturnCaseStatus.recovery_required) {
            throw ApiExceptions.conflict("Không thể hủy nhiệm vụ thu hồi khi hàng vẫn đang chờ xử lý.");
        }

        task.setStatus(MaintenanceTaskStatus.cancelled);
        if (reason != null && !reason.isBlank()) {
            task.setResultReport("Đã hủy nhiệm vụ: " + reason.trim());
        }

        MaintenanceTask saved = maintenanceTaskRepository.save(task);
        StorageUnit unit = saved.getStorageUnit();
        if (unit != null && unit.getStatus() == StorageUnitStatus.maintenance
            && saved.getTaskType() != MaintenanceTaskType.inventory_check
            && !hasOtherActiveMaintenanceTasks(unit.getId(), saved.getId())) {
            unit.setStatus(StorageUnitStatus.available);
            storageUnitRepository.save(unit);
        }

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

    private boolean hasOtherActiveMaintenanceTasks(UUID storageUnitId, UUID excludeTaskId) {
        return maintenanceTaskRepository.findByStorageUnit_IdAndStatusIn(
            storageUnitId, List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        ).stream().anyMatch(other -> !other.getId().equals(excludeTaskId)
            && other.getTaskType() != MaintenanceTaskType.inventory_check);
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
        if (!actor.hasPermission(SystemPermission.VIEW_UNITS)
            && !actor.hasPermission(SystemPermission.MANAGE_STAFF_TASKS)) {
            authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        }

        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        Collection<UUID> allowedFacilityIds = List.of(new UUID(0, 0));
        if (scoped) {
            if (!actor.facilityScopes().isEmpty()) allowedFacilityIds = actor.facilityScopes().keySet();
            if (facilityId != null) {
                facilityScopeService.assertCanRead(actor, facilityId);
            }
        }

        Pageable pageable = pageable(page, pageSize);
        if (scoped && allowedFacilityIds.isEmpty()) {
            return PageResponse.from(Page.<MaintenanceTaskResponse>empty(pageable), correlationId);
        }
        Page<MaintenanceTaskResponse> result = maintenanceTaskRepository.searchTasks(
            facilityId,
            storageUnitId,
            status,
            priority,
            assignedStaffId,
            scoped,
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
        User customer = t.getRental() != null ? t.getRental().getCustomer()
            : t.getReturnCase() != null ? t.getReturnCase().getCustomer() : null;
        return new MaintenanceTaskResponse(
            t.getId(),
            t.getFacility().getId(),
            t.getFacility().getName(),
            t.getStorageUnit().getId(),
            t.getStorageUnit().getCode(),
            t.getRental() != null ? t.getRental().getId() : null,
            customer != null ? customer.getFullName() : null,
            t.getReturnCase() != null ? t.getReturnCase().getId() : null,
            t.getTitle(),
            t.getTaskType() == null ? MaintenanceTaskType.maintenance : t.getTaskType(),
            t.getReason(),
            t.getPriority(),
            t.getDamageClassification(),
            t.getReportedBy() != null ? t.getReportedBy().getId() : null,
            t.getReportedBy() != null ? t.getReportedBy().getFullName() : null,
            t.getAssignedTo() != null ? t.getAssignedTo().getId() : null,
            t.getAssignedTo() != null ? t.getAssignedTo().getFullName() : null,
            t.getStatus(),
            t.getRecoveryType() != null && t.getRecoveryType() != MaintenanceRecoveryType.NONE
                || t.getReturnCase() != null
                && t.getReturnCase().getStatus() == com.storagehub.domain.model.ReturnCaseStatus.recovery_required,
            t.getRecoveryType() == null ? MaintenanceRecoveryType.NONE : t.getRecoveryType(),
            t.getDueAt(),
            t.getDeadlineAt(),
            t.getRecoveryHandoverLocation(),
            t.getRecoveryWitness() != null ? t.getRecoveryWitness().getId() : null,
            t.getRecoveryWitness() != null ? t.getRecoveryWitness().getFullName() : null,
            t.getStartedAt(),
            t.getCompletedAt(),
            t.getResultReport(),
            evidencePhotos,
            t.getCreatedAt(),
            t.getUpdatedAt()
        );
    }

    private void assertEligibleStaff(User staff, UUID facilityId) {
        boolean isStaff = staff.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.STAFF);
        boolean hasFacilityScope = userFacilityScopeRepository.findByUser_IdAndFacility_Id(staff.getId(), facilityId)
            .map(scope -> scope.getScopeLevel().includes(FacilityScopeLevel.OPERATE))
            .orElse(false);
        if (staff.getStatus() != UserStatus.ACTIVE || !isStaff || !hasFacilityScope) {
            throw ApiExceptions.forbidden("Assigned user must be an active Staff with OPERATE scope at this facility");
        }
    }

    private User requireRecoveryWitness(UUID witnessId, UUID facilityId, UUID actorId) {
        if (witnessId == null) {
            throw ApiExceptions.validation("Cần chọn nhân viên thứ hai cùng xác nhận việc thu hồi hàng.", null);
        }
        if (witnessId.equals(actorId)) {
            throw ApiExceptions.conflict("Nhân viên xác nhận thứ hai phải khác người đang hoàn tất nhiệm vụ.");
        }
        User witness = userRepository.findById(witnessId)
            .orElseThrow(() -> ApiExceptions.notFound("Nhân viên xác nhận thứ hai không tồn tại."));
        assertEligibleStaff(witness, facilityId);
        return witness;
    }
}
