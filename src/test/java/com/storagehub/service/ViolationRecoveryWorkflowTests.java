package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.model.ViolationRecovery.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ViolationRecoveryWorkflowTests {
    private final ViolationRecoveryRepository cases = mock(ViolationRecoveryRepository.class);
    private final RentalRepository rentals = mock(RentalRepository.class);
    private final ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    private final MaintenanceTaskRepository tasks = mock(MaintenanceTaskRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileAssetRepository files = mock(FileAssetRepository.class);
    private final AccessCredentialService access = mock(AccessCredentialService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ViolationRecoveryWorkflow workflow = new ViolationRecoveryWorkflow(cases, rentals, returns, tasks, users,
        files, access, mock(UnitAssignmentRepository.class), notifications, mock(AuditLogService.class),
        new FacilityScopeService(), new WorkflowBusinessCalendar(mock(SystemSettingRepository.class), mapper),
        mock(EntityManager.class), mapper);
    private final Facility facility = identified(new Facility());
    private final User customer = identified(new User());
    private final User staff = identified(new User());
    private final User witness = identified(new User());
    private final User manager = identified(new User());
    private final Rental rental = identified(new Rental());
    private final ViolationRecovery recovery = identified(new ViolationRecovery());
    private final ReturnCase source = identified(new ReturnCase());
    private final StorageUnit unit = identified(new StorageUnit());
    private final MaintenanceTask task = identified(new MaintenanceTask());

    @BeforeEach
    void setUp() {
        var booking = identified(new Reservation());
        booking.setIdentityDocumentNumber("TEST99999");
        rental.setReservation(booking);
        rental.setCustomer(customer);
        rental.setFacility(facility);
        unit.setCode("GA-01");
        unit.setStatus(StorageUnitStatus.occupied);
        unit.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setStatus(RentalStatus.return_requested);
        rental.setInventoryRemovalDeadlineAt(Instant.now().minusSeconds(120));
        source.setRental(rental);
        source.setStatus(ReturnCaseStatus.scheduled);
        recovery.setRental(rental);
        recovery.setSourceReturnId(source.getId());
        recovery.setReason(Reason.REMOVAL_OVERDUE);
        recovery.setSourceDeadlineAt(rental.getInventoryRemovalDeadlineAt());
        recovery.setStage(Stage.REVIEW_REQUIRED);
        task.setAssignedTo(staff);
        when(cases.findById(recovery.getId())).thenReturn(Optional.of(recovery));
        when(cases.lock(recovery.getId())).thenReturn(Optional.of(recovery));
        when(rentals.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returns.findByIdForUpdate(source.getId())).thenReturn(Optional.of(source));
        when(users.findEligibleStaff(eq(UserStatus.ACTIVE), eq(RoleCode.STAFF), eq(facility.getId()), anyList()))
            .thenReturn(List.of(staff, witness));
        when(users.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(tasks.saveAndFlush(any())).thenAnswer(call -> identified(call.getArgument(0)));
    }

    @Test
    void opensOneRequestAndUses72HoursOnlyAfterManagerConfirms() {
        assertThat(workflow.detect(rental, source, Reason.REMOVAL_OVERDUE, rental.getInventoryRemovalDeadlineAt(),
            Instant.now(), true)).isTrue();
        verify(tasks, never()).saveAndFlush(any());
        when(cases.findByRental_IdOrderByCreatedAtDesc(rental.getId())).thenReturn(List.of(recovery));
        assertThat(workflow.detect(rental, source, Reason.REMOVAL_OVERDUE, rental.getInventoryRemovalDeadlineAt(),
            Instant.now(), true)).isFalse();
        var view = execute(manager(), ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION, "Đã xác minh căn cứ", null);
        assertThat(view.deadlineAt()).isEqualTo(view.requiredAt().plus(Duration.ofHours(72)));
        assertThat(view.taskId()).isNull();
        assertThat(source.getStatus()).isEqualTo(ReturnCaseStatus.withdrawn_for_recovery);
    }

    @Test
    void disputeBlocksStartAndDoesNotResetTheDeadline() throws Exception {
        var before = readyForAbsentHandover();
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        assertThat(execute(customerActor, ViolationRecoveryWorkflow.Action.DISPUTE, "Tôi đã đến cơ sở", null).stage()).isEqualTo(Stage.BLOCKED);
        assertThatThrownBy(() -> execute(staff(), ViolationRecoveryWorkflow.Action.START, null, null))
            .isInstanceOf(ApiException.class).hasMessageContaining("chưa đủ điều kiện");
        var actionDue = recovery.getActionDueAt();
        execute(customerActor, ViolationRecoveryWorkflow.Action.DISPUTE, "Bổ sung thông tin", null);
        assertThat(recovery.getActionDueAt()).isEqualTo(actionDue);
        assertThat(recovery.getDeadlineAt()).isEqualTo(before);
        verifyNoInteractions(access);
    }

    @Test
    void emptyUnitCompletionRevokesAccessAndOpensReadinessWithoutFinancialSettlement() throws Exception {
        readyForAbsentHandover();
        UUID before = photo();
        var started = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), before, "Khu lưu tạm A", null));
        assertThat(started.stage()).isEqualTo(Stage.IN_PROGRESS);
        var result = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian vốn trống, đã kiểm tra và xử lý khóa", null, photo(), null, "VACATED"));
        assertThat(result.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(result.pickupDueAt()).isNull();
        assertThat(result.financePending()).isFalse();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);
        verify(access).revokeForRental(any(), eq(rental), anyString());
        verify(tasks).save(argThat(created -> created.getRecoveryType() == MaintenanceRecoveryType.VIOLATION_UNIT_READINESS));
    }

    @Test
    void followUpExpiryStopsStaffInspectionAndQueuesReviewWithoutLosingArrivalProtection() {
        var now = Instant.now();
        source.setReturnType(ReturnType.INVENTORY_MISMATCH);
        source.setReturnArrivedAt(now.minus(Duration.ofMinutes(40)));
        source.setReturnIdentityVerifiedAt(now.minus(Duration.ofMinutes(39)));
        source.setReturnArrivalReviewedAt(now.minus(Duration.ofMinutes(5)));
        source.setReturnArrivalReviewAccepted(true);
        source.setPostReviewHandoverDueAt(now);
        source.getRental().setInventoryRemovalDeadlineAt(now.minusSeconds(120));
        assertThat(ReturnAttendancePolicy.canInspect(source, now.minusNanos(1))).isTrue();
        assertThat(ReturnAttendancePolicy.canInspect(source, now)).isFalse();
        assertThat(ReturnAttendancePolicy.reviewStartsAt(source)).isEqualTo(now);
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(source)).isTrue();
    }

    @Test
    void storedItemsCanBeReleasedOnlyOnceWithSeparatePickupVerification() throws Exception {
        readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SAVE_ITEM, null, null, photo(), null, null,
            null, null, "PKG-1", "Kiện mô phỏng", "Nguyên vẹn", "TEMP-A", "STORED"));
        var completed = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian trống, đã xử lý khóa", null, photo(), null, "VACATED"));
        assertThat(completed.pickupDueAt()).isNotNull();
        assertThat(completed.actions()).contains(ViolationRecoveryWorkflow.Action.OPEN_PICKUP)
            .doesNotContain(ViolationRecoveryWorkflow.Action.RELEASE_ITEM);
        execute(staff(), ViolationRecoveryWorkflow.Action.OPEN_PICKUP, null, null);
        workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.VERIFY_PICKUP, null, null, null, UUID.randomUUID(), null,
            null, "TEST99999", null, null, null, null, null));
        var release = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RELEASE_ITEM,
            null, null, photo(), null, null, null, null, "PKG-1", null, null, null, null);
        var released = workflow.execute(staff(), recovery.getId(), release);
        assertThat(released.items().getFirst().releasedAt()).isNotNull();
        assertThat(recovery.getPickupReviewAt()).isNull();
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), release)).isInstanceOf(ApiException.class);
    }

    private Instant readyForAbsentHandover() throws Exception {
        var now = Instant.now();
        recovery.setTask(task);
        recovery.setStage(Stage.APPROVED);
        recovery.setRequiredAt(now.minus(Duration.ofHours(26)));
        recovery.setScheduledAt(now.minus(Duration.ofHours(2)));
        recovery.setDeadlineAt(recovery.getRequiredAt().plus(Duration.ofHours(72)));
        var details = new ViolationRecoveryWorkflow.Details();
        details.attendance = Attendance.ABSENT;
        details.acceptedAt = now.minus(Duration.ofHours(25));
        details.notifiedAt = now.minus(Duration.ofHours(24));
        details.responseDueAt = now.minus(Duration.ofHours(18));
        details.needsWitness = true;
        recovery.setDetailsJson(mapper.writeValueAsString(details));
        return recovery.getDeadlineAt();
    }

    private UUID photo() {
        var file = identified(new FileAsset());
        file.setStatus(FileAssetStatus.ACTIVE);
        file.setEntityType("MAINTENANCE_TASK");
        file.setEntityId(task.getId());
        file.setUploadedBy(staff);
        file.setContentType("image/jpeg");
        when(files.findById(file.getId())).thenReturn(Optional.of(file));
        return file.getId();
    }

    private ActorPrincipal manager() { return actor(manager, RoleCode.MANAGER, SystemPermission.MANAGE_STAFF_TASKS, FacilityScopeLevel.MANAGE); }
    private ActorPrincipal staff() { return actor(staff, RoleCode.STAFF, SystemPermission.PROCESS_RETURNS, FacilityScopeLevel.OPERATE); }

    private ActorPrincipal actor(User user, RoleCode role, SystemPermission permission, FacilityScopeLevel level) {
        return new ActorPrincipal(user.getId(), UUID.randomUUID(), Set.of(role),
            permission == null ? Set.of() : Set.of(permission.code()), level == null ? Map.of() : Map.of(facility.getId(), level));
    }

    private ViolationRecoveryWorkflow.Entry execute(ActorPrincipal actor, ViolationRecoveryWorkflow.Action action, String note, UUID witnessId) {
        return workflow.execute(actor, recovery.getId(), command(action, note, witnessId, null, null, null));
    }

    private static ViolationRecoveryWorkflow.Command command(ViolationRecoveryWorkflow.Action action, String note, UUID witnessId,
        UUID evidenceId, String location, String disposition) {
        return new ViolationRecoveryWorkflow.Command(action, null, witnessId, evidenceId, null, null,
            note, null, null, null, null, location, disposition);
    }

    private static <T extends BaseEntity> T identified(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
