package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MismatchUnitRelease {
    static final String TASK_TITLE = "Kiểm tra và mở lại gian sau trả kho do sai lệch";
    private final MaintenanceTaskRepository tasks;
    private final UnitAssignmentRepository assignments;
    private final RentalRepository rentals;
    private final AccessCredentialRepository credentials;
    private final WorkflowBusinessCalendar calendar;

    static boolean isReleaseTask(MaintenanceTask task) {
        return task.getRecoveryType() == MaintenanceRecoveryType.VIOLATION_UNIT_READINESS
            || TASK_TITLE.equals(task.getTitle()) && task.getReturnCase() != null
            && task.getReturnCase().getReturnType() == ReturnType.INVENTORY_MISMATCH;
    }

    void open(ReturnCase rc, StorageUnit lockedUnit, User staff, Instant now) {
        var task = new MaintenanceTask();
        task.setTitle(TASK_TITLE);
        task.setReturnCase(rc);
        task.setRental(rc.getRental());
        task.setStorageUnit(lockedUnit);
        task.setFacility(rc.getFacility());
        task.setReportedBy(staff);
        task.setReason("Kiểm tra gian trống, khóa và quyền truy cập cũ. Hoàn tất vệ sinh và bảo trì trước khi mở lại.");
        task.setPriority(TaskPriority.high);
        task.setDeadlineAt(calendar.current().afterWorkingHours(now, 4));
        task.setDueAt(task.getDeadlineAt().atZone(InventoryTimelinePolicy.ZONE).toLocalDate());
        tasks.save(task);
    }

    void verifyReady(MaintenanceTask task, StorageUnit unit) {
        var rc = task.getReturnCase();
        boolean violation = task.getRecoveryType() == MaintenanceRecoveryType.VIOLATION_UNIT_READINESS;
        var rental = violation ? task.getRental() : rc.getRental();
        if (rental == null || !unit.getId().equals(rental.getStorageUnit().getId())
            || unit.getStatus() != StorageUnitStatus.maintenance || rental.getActualReturnedAt() == null
            || (violation ? rental.getEndReason() != RentalEndReason.VIOLATION_RECOVERY
                || !unit.getId().equals(task.getStorageUnit().getId()) || !unit.getFacility().getId().equals(rental.getFacility().getId())
                : !unit.getId().equals(rc.getStorageUnit().getId()) || !unit.getFacility().getId().equals(rc.getFacility().getId())
                    || rc.getInspectedAt() == null || !Boolean.TRUE.equals(rc.getWarehouseEmptyVerified())))
            throw ApiExceptions.conflict("Cần nghiệm thu gian trống trước khi mở lại.");
        if (credentials.existsByRental_IdAndStatus(rental.getId(), AccessCredentialStatus.ACTIVE)
            || credentials.existsByReservation_IdAndStatus(rental.getReservation().getId(), AccessCredentialStatus.ACTIVE))
            throw ApiExceptions.conflict("Cần thu hồi toàn bộ quyền truy cập cũ trước khi mở lại gian.");
        if (rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId()))
            throw ApiExceptions.conflict("Gian kho đang thuộc hồ sơ thuê khác.");
        var oldAssignment = assignments.findByReservation_IdAndStatus(rental.getReservation().getId(), UnitAssignmentStatus.ACTIVE);
        if (assignments.existsOtherActiveAssignment(unit.getId(), rental.getReservation().getId()))
            throw ApiExceptions.conflict("Gian kho đã được phân cho đơn khác.");
        if (hasOtherWork(unit.getId(), task.getId()))
            throw ApiExceptions.conflict("Cần hoàn tất các nhiệm vụ bảo trì hoặc thu hồi còn mở trước khi mở lại gian.");
        oldAssignment.filter(a -> a.getStorageUnit().getId().equals(unit.getId()))
            .ifPresent(a -> a.setStatus(UnitAssignmentStatus.COMPLETED));
    }

    private boolean hasOtherWork(UUID unitId, UUID taskId) {
        return tasks.findByStorageUnit_IdAndStatusIn(unitId, List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress))
            .stream().anyMatch(task -> !taskId.equals(task.getId())
                && (task.getTaskType() != MaintenanceTaskType.inventory_check || task.getRecoveryType() != MaintenanceRecoveryType.NONE));
    }
}
