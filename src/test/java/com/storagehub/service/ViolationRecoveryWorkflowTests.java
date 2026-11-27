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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class ViolationRecoveryWorkflowTests {
    private final ViolationRecoveryRepository cases = mock(ViolationRecoveryRepository.class);
    private final RentalRepository rentals = mock(RentalRepository.class);
    private final ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    private final MaintenanceTaskRepository tasks = mock(MaintenanceTaskRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileAssetRepository files = mock(FileAssetRepository.class);
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final UserFacilityScopeRepository userFacilityScopes = mock(UserFacilityScopeRepository.class);
    private final AccessCredentialService access = mock(AccessCredentialService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RecoveryStaffAvailability staffAvailability = mock(RecoveryStaffAvailability.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ViolationRecoveryWorkflow workflow = new ViolationRecoveryWorkflow(cases, rentals, returns, tasks, users,
        files, access, mock(UnitAssignmentRepository.class), notifications, mock(AuditLogService.class),
        new FacilityScopeService(), new WorkflowBusinessCalendar(mock(SystemSettingRepository.class), mapper),
        mock(EntityManager.class), mapper, new RecoveryReviewService(checkIns, returns, files, mapper,
            mock(ReservationGoodsItemRepository.class)), userFacilityScopes, staffAvailability);
    private final Facility facility = identified(new Facility());
    private final User customer = identified(new User());
    private final User staff = identified(new User());
    private final User witness = identified(new User());
    private final User manager = identified(new User());
    private final User business = identified(new User());
    private final Rental rental = identified(new Rental());
    private final ViolationRecovery recovery = identified(new ViolationRecovery());
    private final ReturnCase source = identified(new ReturnCase());
    private final StorageUnit unit = identified(new StorageUnit());
    private final MaintenanceTask task = identified(new MaintenanceTask());

    @BeforeEach
    void setUp() {
        var booking = identified(new Reservation());
        booking.setReservationCode("RSV-TEST-10");
        booking.setIdentityDocumentNumber("TEST99999");
        rental.setReservation(booking);
        rental.setCustomer(customer);
        rental.setFacility(facility);
        unit.setCode("GA-01");
        customer.setFullName("Khách thử nghiệm");
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
        var staffRole = new Role();
        staffRole.setCode(RoleCode.STAFF);
        var permission = new Permission();
        permission.setCode(SystemPermission.PROCESS_RETURNS.code());
        staffRole.getPermissions().add(permission);
        staff.getRoles().add(staffRole);
        witness.getRoles().add(staffRole);
        when(staffAvailability.available(any(), any(), any(), any())).thenReturn(true);
        when(cases.saveAndFlush(any())).thenAnswer(call -> identified(call.getArgument(0)));
        when(cases.findById(recovery.getId())).thenReturn(Optional.of(recovery));
        when(cases.lock(recovery.getId())).thenReturn(Optional.of(recovery));
        when(rentals.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returns.findByIdForUpdate(source.getId())).thenReturn(Optional.of(source));
        when(returns.findById(source.getId())).thenReturn(Optional.of(source));
        when(users.findEligibleStaff(eq(UserStatus.ACTIVE), eq(RoleCode.STAFF), eq(facility.getId()), anyList()))
            .thenReturn(List.of(staff, witness));
        when(users.findById(manager.getId())).thenReturn(Optional.of(manager));
        business.setFullName("Demo BO");
        when(users.findById(business.getId())).thenReturn(Optional.of(business));
        when(tasks.saveAndFlush(any())).thenAnswer(call -> identified(call.getArgument(0)));
    }

    @Test
    void unassignedProhibitedRecoveryIsVisibleToStaffInFacilityButCannotBeOperated() {
        recovery.setReason(Reason.CONFIRMED_PROHIBITED_GOODS);
        recovery.setStage(Stage.AWAITING_ASSIGNMENT);
        recovery.setTask(null);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(workflow, "canRead", staff(), recovery)).isTrue();
        var details = new ViolationRecoveryWorkflow.Details();
        List<?> actions = ReflectionTestUtils.invokeMethod(workflow, "allowed", staff(), recovery, details, Instant.now());
        assertThat(actions).isEmpty();
        var foreignStaff = new ActorPrincipal(staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of(SystemPermission.PROCESS_RETURNS.code()), Map.of(UUID.randomUUID(), FacilityScopeLevel.OPERATE));
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(workflow, "canRead", foreignStaff, recovery)).isFalse();
        recovery.setReason(Reason.SETUP_OVERDUE);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(workflow, "canRead", staff(), recovery)).isFalse();
    }

    @Test
    void inventoryProhibitedGoodsStartsRecoveryFromSetupWithEvidenceAndCannotRepeat() throws Exception {
        rental.setStatus(RentalStatus.setup);
        staff.setFullName("Nhân viên kiểm kê");
        when(users.getReferenceById(staff.getId())).thenReturn(staff);
        UUID evidence = UUID.randomUUID();
        UUID id = workflow.openInventoryProhibitedGoodsRecovery(staff(), rental, "Hàng cấm: bình hóa chất", List.of(evidence));
        var captor = org.mockito.ArgumentCaptor.forClass(ViolationRecovery.class);
        verify(cases, atLeastOnce()).saveAndFlush(captor.capture());
        var opened = captor.getValue();
        assertThat(opened.getId()).isEqualTo(id);
        assertThat(opened.getReason()).isEqualTo(Reason.CONFIRMED_PROHIBITED_GOODS);
        assertThat(opened.getStage()).isEqualTo(Stage.AWAITING_ASSIGNMENT);
        assertThat(opened.getPreviousRentalStatus()).isEqualTo(RentalStatus.setup);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.closing);
        var details = mapper.readTree(opened.getDetailsJson());
        assertThat(details.path("report").asText()).contains("bình hóa chất");
        assertThat(details.path("inventoryEvidenceIds").get(0).asText()).isEqualTo(evidence.toString());
        assertThatThrownBy(() -> workflow.openInventoryProhibitedGoodsRecovery(staff(), rental, "Hàng cấm", List.of(evidence)))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void confirmedProhibitedGoodsEntersOrdinaryAssignmentAndOpensOnlyOnce() {
        rental.setStatus(RentalStatus.active);
        UUID id = workflow.openConfirmedProhibitedGoodsRecovery(manager(), rental.getId());

        var created = org.mockito.ArgumentCaptor.forClass(ViolationRecovery.class);
        verify(cases).saveAndFlush(created.capture());
        var opened = created.getValue();
        assertThat(id).isEqualTo(opened.getId());
        assertThat(opened.getReason()).isEqualTo(Reason.CONFIRMED_PROHIBITED_GOODS);
        assertThat(opened.getStage()).isEqualTo(Stage.AWAITING_ASSIGNMENT);
        assertThat(opened.getScheduledAt()).isEqualTo(opened.getRequiredAt().plus(Duration.ofHours(24)));
        assertThat(opened.getPreviousRentalStatus()).isEqualTo(RentalStatus.active);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.closing);
        assertThatThrownBy(() -> workflow.openConfirmedProhibitedGoodsRecovery(manager(), rental.getId()))
            .isInstanceOfSatisfying(com.storagehub.common.api.ApiException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(tasks, never()).saveAndFlush(any());
    }

    @Test
    void automaticallyOpensOneRequestAndSchedules24HoursThen48HoursToComplete() {
        assertThat(workflow.detect(rental, source, Reason.REMOVAL_OVERDUE, rental.getInventoryRemovalDeadlineAt(),
            Instant.now(), true)).isTrue();
        verify(tasks, never()).saveAndFlush(any());
        var created = org.mockito.ArgumentCaptor.forClass(ViolationRecovery.class);
        verify(cases).saveAndFlush(created.capture());
        when(cases.findByRental_IdOrderByCreatedAtDesc(rental.getId())).thenReturn(List.of(created.getValue()));
        assertThat(workflow.detect(rental, source, Reason.REMOVAL_OVERDUE, rental.getInventoryRemovalDeadlineAt(),
            Instant.now(), true)).isFalse();
        assertThat(created.getValue().getStage()).isEqualTo(Stage.AWAITING_ASSIGNMENT);
        assertThat(created.getValue().getScheduledAt()).isEqualTo(created.getValue().getRequiredAt().plus(Duration.ofHours(24)));
        assertThat(created.getValue().getDeadlineAt()).isEqualTo(created.getValue().getScheduledAt().plus(Duration.ofHours(48)));
        assertThat(source.getStatus()).isEqualTo(ReturnCaseStatus.withdrawn_for_recovery);
    }

    @Test
    void preparationRequiresOwnerOrAssignedStaffOrAnOperationsRoleInTheFacility() throws Exception {
        readyForAbsentHandover();
        assertThat(workflow.preparation(staff(), recovery.getId()).declared()).isEmpty();
        assertThat(workflow.preparation(manager(), recovery.getId()).declared()).isEmpty();
        assertThat(workflow.preparation(business(), recovery.getId()).declared()).isEmpty();
        assertThatThrownBy(() -> workflow.preparation(actor(witness, RoleCode.STAFF, SystemPermission.PROCESS_RETURNS,
            FacilityScopeLevel.OPERATE), recovery.getId())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        var outside = new ActorPrincipal(manager.getId(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.MANAGE_STAFF_TASKS.code()), Map.of(UUID.randomUUID(), FacilityScopeLevel.MANAGE));
        assertThatThrownBy(() -> workflow.preparation(outside, recovery.getId())).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(workflow.preparation(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId()).declared()).isEmpty();
        assertThatThrownBy(() -> workflow.preparation(actor(identified(new User()), RoleCode.CUSTOMER, null, null), recovery.getId()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(workflow.get(staff(), recovery.getId()).canViewSourceGoods()).isTrue();
        assertThat(workflow.get(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId()).canViewSourceGoods()).isTrue();
    }

    @Test
    void sourceReturnLinkRespectsCustomerVisibilityAndHidesCancelledCases() {
        when(cases.findBySourceReturnIdOrderByCreatedAtDesc(source.getId())).thenReturn(List.of(recovery));
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        assertThat(workflow.listForReturn(customerActor, source.getId())).isEmpty();
        assertThat(workflow.listForReturn(manager(), source.getId())).extracting(ViolationRecoveryWorkflow.Entry::id)
            .containsExactly(recovery.getId());
        recovery.setRequiredAt(Instant.now());
        assertThat(workflow.listForReturn(customerActor, source.getId())).hasSize(1);
        assertThat(workflow.listForReturn(actor(identified(new User()), RoleCode.CUSTOMER, null, null), source.getId())).isEmpty();
        recovery.setStage(Stage.CANCELLED);
        assertThat(workflow.listForReturn(customerActor, source.getId())).isEmpty();
    }

    @Test
    void disputeBlocksStartAndDoesNotResetTheDeadline() throws Exception {
        var before = activateRecovery().deadlineAt();
        recovery.setTask(task);
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        assertThat(execute(customerActor, ViolationRecoveryWorkflow.Action.DISPUTE, "Tôi đã đến cơ sở", null).stage()).isEqualTo(Stage.BLOCKED);
        assertThatThrownBy(() -> execute(staff(), ViolationRecoveryWorkflow.Action.START, null, null))
            .isInstanceOf(ApiException.class).hasMessageContaining("chưa đủ điều kiện");
        var actionDue = recovery.getActionDueAt();
        assertThatThrownBy(() -> execute(customerActor, ViolationRecoveryWorkflow.Action.DISPUTE, "Bổ sung thông tin", null)).isInstanceOf(ApiException.class);
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
    void pausePreventsAddingItemsAndCompletionUntilManagerResumesWithoutAnotherApproval() throws Exception {
        var deadline = readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        var firstItem = new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SAVE_ITEM, null, null, photo(), null, null,
            null, null, "PKG-1", "Kiện đã lưu", "Nguyên vẹn", "TEMP-A", "STORED");
        var saved = workflow.execute(staff(), recovery.getId(), firstItem);
        pauseForReview("Cần quản lý rà soát");

        when(cases.findBySourceReturnIdOrderByCreatedAtDesc(source.getId())).thenReturn(List.of(recovery));
        var paused = workflow.listForReturn(staff(), source.getId()).getFirst();
        assertThat(paused.stage()).isEqualTo(Stage.BLOCKED);
        assertThat(paused.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
            ViolationRecoveryWorkflow.Action.COMPLETE, ViolationRecoveryWorkflow.Action.START);
        assertThat(paused.items()).containsExactlyElementsOf(saved.items());
        var pausedDetails = recovery.getDetailsJson();
        var nextItem = new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SAVE_ITEM, null, null, photo(), null, null,
            null, null, "PKG-2", "Kiện mới", "Nguyên vẹn", "TEMP-B", "STORED");
        var completion = command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian trống", null, photo(), null, "VACATED");
        for (var blockedCommand : List.of(nextItem, completion)) {
            assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), blockedCommand))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(recovery.getDetailsJson()).isEqualTo(pausedDetails);
        assertThat(recovery.getCompletedAt()).isNull();
        verifyNoInteractions(access);

        execute(manager(), ViolationRecoveryWorkflow.Action.RESUME, "Đã xử lý, cho tiếp tục", null);
        assertThat(workflow.execute(staff(), recovery.getId(), nextItem).items()).hasSize(2);
        assertThat(workflow.execute(staff(), recovery.getId(), completion).stage()).isEqualTo(Stage.COMPLETED);
        assertThat(recovery.getDeadlineAt()).isEqualTo(deadline);
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
        assertThat(completed.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.OPEN_PICKUP,
            ViolationRecoveryWorkflow.Action.RELEASE_ITEM);
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, Instant.now(),
            null, null, null, null, null, null, null));
        workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN_PICKUP, staff.getId(), null, null, null, null,
            null, null, null, null, null, null, null));
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

    @Test
    void businessRecordsLiquidationPerItemAndPreventsDuplicateOrLaterPickup() throws Exception {
        completeWithStoredItems(2);
        when(cases.findBySourceReturnIdOrderByCreatedAtDesc(source.getId())).thenReturn(List.of(recovery));
        var before = workflow.listForReturn(business(), source.getId()).getFirst();
        var evidenceId = photo(business);
        var command = liquidation("PKG-1", evidenceId, "BB-TL-01: Đã xử lý theo biên bản", "LIQUIDATED");
        var result = workflow.execute(business(), recovery.getId(), command);

        var first = result.items().getFirst();
        assertThat(first.liquidation().recordedBy()).isEqualTo(business.getId());
        assertThat(first.liquidation().evidenceId()).isEqualTo(evidenceId);
        assertThat(first.liquidation().note()).contains("BB-TL-01");
        assertThat(first.liquidation().recordedAt()).isNotNull();
        assertThat(first.releasedAt()).isNull();
        assertThat(first.location()).isEqualTo(before.items().getFirst().location());
        assertThat(first.evidenceId()).isEqualTo(before.items().getFirst().evidenceId());
        assertThat(result.items().get(1)).isEqualTo(before.items().get(1));
        assertThat(result.pickupReviewAt()).isEqualTo(before.pickupReviewAt());
        assertThat(result.actions()).contains(ViolationRecoveryWorkflow.Action.RECORD_LIQUIDATION);
        assertThat(result.history().getLast().actorName()).isEqualTo("Demo BO");
        assertThat(result.history().getLast().note()).contains("PKG-1", "BB-TL-01");
        assertThat(workflow.listForReturn(business(), source.getId()).getFirst().items()).isEqualTo(result.items());

        var customerView = workflow.listForReturn(actor(customer, RoleCode.CUSTOMER, null, null), source.getId()).getFirst();
        var customerLiquidation = customerView.items().getFirst().liquidation();
        assertThat(customerLiquidation.recordedAt()).isEqualTo(first.liquidation().recordedAt());
        assertThat(customerLiquidation.recordedBy()).isNull();
        assertThat(customerLiquidation.note()).isNull();
        assertThat(customerLiquidation.evidenceId()).isNull();
        assertThatThrownBy(() -> workflow.execute(business(), recovery.getId(), command))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, Instant.now(),
            null, null, null, null, null, null, null));
        workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN_PICKUP, staff.getId(), null, null, null, null,
            null, null, null, null, null, null, null));
        execute(staff(), ViolationRecoveryWorkflow.Action.OPEN_PICKUP, null, null);
        workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.VERIFY_PICKUP, null, null, null, UUID.randomUUID(), null,
            null, "TEST99999", null, null, null, null, null));
        var release = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RELEASE_ITEM,
            null, null, photo(), null, null, null, null, "PKG-1", null, null, null, null);
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), release))
            .isInstanceOf(ApiException.class).hasMessageContaining("không còn chờ nhận");
    }

    @Test
    void liquidatingLastPendingItemStopsPickupReviewAndKeepsFinanceAndUnitUnchanged() throws Exception {
        completeWithStoredItems(1);
        recovery.setPickupEscalated(true);
        recovery.setFinancePending(true);
        var result = workflow.execute(business(), recovery.getId(), liquidation("PKG-1", photo(business), "BB-TL-02", "LIQUIDATED"));
        assertThat(result.pickupReviewAt()).isNull();
        assertThat(result.pickupEscalated()).isFalse();
        assertThat(result.financePending()).isTrue();
        assertThat(result.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(result.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.RECORD_LIQUIDATION,
            ViolationRecoveryWorkflow.Action.FOLLOW_UP_PICKUP, ViolationRecoveryWorkflow.Action.ASSIGN_PICKUP);
        when(cases.findBySourceReturnIdOrderByCreatedAtDesc(source.getId())).thenReturn(List.of(recovery));
        assertThat(workflow.listForReturn(staff(), source.getId()).getFirst().actions())
            .doesNotContain(ViolationRecoveryWorkflow.Action.OPEN_PICKUP, ViolationRecoveryWorkflow.Action.RELEASE_ITEM);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);
        verify(tasks, times(1)).save(any());
        verify(access, times(1)).revokeForRental(any(), eq(rental), anyString());
    }

    @Test
    void onlyBusinessWithManagementScopeCanRecordLiquidationAfterRecovery() throws Exception {
        completeWithStoredItems(1);
        var command = liquidation("PKG-1", photo(business), "Biên bản", "LIQUIDATED");
        var original = recovery.getDetailsJson();
        for (var unauthorized : List.of(manager(), staff(), actor(customer, RoleCode.CUSTOMER, null, null),
            actor(business, RoleCode.BUSINESS, null, FacilityScopeLevel.READ))) {
            assertThatThrownBy(() -> workflow.execute(unauthorized, recovery.getId(), command))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(recovery.getDetailsJson()).isEqualTo(original);
        }
        var otherFacility = new ActorPrincipal(business.getId(), UUID.randomUUID(), Set.of(RoleCode.BUSINESS),
            Set.of(), Map.of(UUID.randomUUID(), FacilityScopeLevel.MANAGE));
        assertThatThrownBy(() -> workflow.execute(otherFacility, recovery.getId(), command)).isInstanceOf(ApiException.class);
        recovery.setStage(Stage.IN_PROGRESS);
        assertThatThrownBy(() -> workflow.execute(business(), recovery.getId(), command)).isInstanceOf(ApiException.class);
        assertThat(recovery.getDetailsJson()).isEqualTo(original);
    }

    @Test
    void liquidationRequiresConfirmationNoteAndEvidenceUploadedByTheBusinessActorForThisTask() throws Exception {
        completeWithStoredItems(1);
        var original = recovery.getDetailsJson();
        var validEvidence = photo(business);
        var wrongTaskEvidence = photo(business);
        var wrongTaskFile = files.findById(wrongTaskEvidence).orElseThrow();
        wrongTaskFile.setEntityId(UUID.randomUUID());
        for (var invalid : List.of(
            liquidation("PKG-1", validEvidence, "Biên bản", null),
            liquidation("PKG-1", validEvidence, " ", "LIQUIDATED"),
            liquidation("PKG-1", null, "Biên bản", "LIQUIDATED"),
            liquidation("PKG-1", photo(), "Biên bản", "LIQUIDATED"),
            liquidation("PKG-1", wrongTaskEvidence, "Biên bản", "LIQUIDATED"))) {
            assertThatThrownBy(() -> workflow.execute(business(), recovery.getId(), invalid))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThat(recovery.getDetailsJson()).isEqualTo(original);
        }
    }

    @Test
    void expiredPickupOnlyEscalatesAndNeverLiquidatesItems() throws Exception {
        completeWithStoredItems(2);
        var due = recovery.getPickupReviewAt();
        workflow.processDue(recovery.getId(), due.plus(Duration.ofDays(30)));
        when(cases.findBySourceReturnIdOrderByCreatedAtDesc(source.getId())).thenReturn(List.of(recovery));
        var result = workflow.listForReturn(business(), source.getId()).getFirst();
        assertThat(result.pickupEscalated()).isTrue();
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.liquidation()).isNull();
            assertThat(item.releasedAt()).isNull();
            assertThat(item.disposition()).isEqualTo("STORED");
        });
        assertThat(result.history()).noneMatch(event -> "RECORD_LIQUIDATION".equals(event.action()));
    }

    @Test
    void customerPickupSavedFirstPreventsLiquidationOfThatItem() throws Exception {
        completeWithStoredItems(2);
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, Instant.now(),
            null, null, null, null, null, null, null));
        workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN_PICKUP, staff.getId(), null, null, null, null,
            null, null, null, null, null, null, null));
        execute(staff(), ViolationRecoveryWorkflow.Action.OPEN_PICKUP, null, null);
        workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.VERIFY_PICKUP, null, null, null, UUID.randomUUID(), null,
            null, "TEST99999", null, null, null, null, null));
        var released = workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.RELEASE_ITEM, null, null, photo(), null, null,
            null, null, "PKG-1", null, null, null, null));
        var before = recovery.getDetailsJson();
        assertThatThrownBy(() -> workflow.execute(business(), recovery.getId(),
            liquidation("PKG-1", photo(business), "Biên bản", "LIQUIDATED")))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(recovery.getDetailsJson()).isEqualTo(before);
        assertThat(released.items().getFirst().releasedAt()).isNotNull();
        assertThat(released.items().getFirst().liquidation()).isNull();
    }

    @Test
    void oldStoredItemJsonWithoutLiquidationFieldStillSupportsManualRecording() throws Exception {
        completeWithStoredItems(1);
        var oldJson = mapper.readTree(recovery.getDetailsJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson.path("items").path("PKG-1")).remove("liquidation");
        recovery.setDetailsJson(mapper.writeValueAsString(oldJson));
        var result = workflow.execute(business(), recovery.getId(), liquidation("PKG-1", photo(business), "Biên bản cũ", "LIQUIDATED"));
        assertThat(result.items().getFirst().liquidation()).isNotNull();
    }

    @Test
    void customerPagingUsesOwnerScopeAndReturnsTotalsInsteadOfFilteringAPageInMemory() {
        activateRecovery();
        var pageable = org.springframework.data.domain.PageRequest.of(1, 8);
        when(cases.customerPage(customer.getId(), pageable)).thenReturn(new org.springframework.data.domain.PageImpl<>(
            Collections.nCopies(5, recovery), pageable, 13));
        var result = workflow.customerPage(actor(customer, RoleCode.CUSTOMER, null, null), 1, 8, "paging");
        assertThat(result.data()).hasSize(5);
        assertThat(result.pagination().totalItems()).isEqualTo(13);
        assertThat(result.pagination().totalPages()).isEqualTo(2);
        assertThat(result.pagination().page()).isEqualTo(1);
        verify(cases).customerPage(customer.getId(), pageable);
        assertThatThrownBy(() -> workflow.customerPage(manager(), 0, 8, "paging"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void activeOperationsQueueNamesTheOrderAndFiltersByTheManagersFacility() {
        when(cases.operationsQueue(eq(false), anyCollection(), eq(true), eq(false), any()))
            .thenReturn(List.of(recovery));
        var queue = workflow.listActiveOperations(manager(), 0);
        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().reservationCode()).isEqualTo("RSV-TEST-10");
        assertThat(queue.getFirst().customerName()).isEqualTo("Khách thử nghiệm");
        verify(cases).operationsQueue(eq(false), eq(Set.of(facility.getId())), eq(true), eq(false), any());
        assertThatThrownBy(() -> workflow.listActiveOperations(staff(), 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workflow.get(actor(identified(new User()), RoleCode.MANAGER, null, null), recovery.getId()))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void businessQueueQueriesOnlyEscalatedManagerStagesBeforePaginationAndKeepsFacilityScope() {
        activateRecovery();
        recovery.setEscalated(true);
        when(cases.findByRental_Facility_IdInAndEscalatedTrueAndStageInOrderByCreatedAtDesc(anyCollection(), anyCollection(), any()))
            .thenReturn(List.of(recovery));
        var queue = workflow.listEscalatedOperations(business(), 2);
        assertThat(queue).extracting(ViolationRecoveryWorkflow.Entry::id).containsExactly(recovery.getId());
        assertThat(queue.getFirst().actions()).contains(ViolationRecoveryWorkflow.Action.ASSIGN);
        verify(cases).findByRental_Facility_IdInAndEscalatedTrueAndStageInOrderByCreatedAtDesc(
            eq(Set.of(facility.getId())), argThat(stages -> stages.contains(Stage.AWAITING_ASSIGNMENT)
                && stages.contains(Stage.BLOCKED) && !stages.contains(Stage.IN_PROGRESS)
                && !stages.contains(Stage.AWAITING_ATTENDANCE) && !stages.contains(Stage.COMPLETED)),
            argThat(page -> page.getPageNumber() == 2 && page.getPageSize() == 30));
        verify(cases, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void globalBusinessQueueHasTheSameOverdueFilterAndCannotBeRequestedByManagerOrStaff() {
        when(cases.findByEscalatedTrueAndStageInOrderByCreatedAtDesc(anyCollection(), any())).thenReturn(List.of());
        var globalBusiness = actor(business, RoleCode.BUSINESS, null, null);
        assertThat(workflow.listEscalatedOperations(globalBusiness, 0)).isEmpty();
        verify(cases).findByEscalatedTrueAndStageInOrderByCreatedAtDesc(anyCollection(), any());
        assertThatThrownBy(() -> workflow.listEscalatedOperations(manager(), 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workflow.listEscalatedOperations(staff(), 0)).isInstanceOf(ApiException.class);
    }

    @Test
    void businessQueueAppearsAfterManagerDeadlineAndLeavesAfterAssignment() {
        activateRecovery();
        when(cases.findByRental_Facility_IdInAndEscalatedTrueAndStageInOrderByCreatedAtDesc(anyCollection(), anyCollection(), any()))
            .thenAnswer(call -> recovery.isEscalated() && recovery.getStage() == Stage.AWAITING_ASSIGNMENT ? List.of(recovery) : List.of());
        var due = recovery.getActionDueAt();
        workflow.processDue(recovery.getId(), due.minusNanos(1));
        assertThat(workflow.listEscalatedOperations(business(), 0)).isEmpty();
        workflow.processDue(recovery.getId(), due);
        assertThat(workflow.listEscalatedOperations(business(), 0)).hasSize(1);
        workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN, staff.getId(), null, null, null, null, null, null, null, null, null, null, null));
        assertThat(recovery.getStage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(recovery.isEscalated()).isFalse();
        assertThat(workflow.listEscalatedOperations(business(), 0)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"AWAITING_ASSIGNMENT", "AWAITING_ATTENDANCE", "BLOCKED", "IN_PROGRESS"})
    void escalatedBusinessHasEveryManagerCoordinationAction(Stage stage) throws Exception {
        activateRecovery();
        recovery.setStage(stage);
        recovery.setEscalated(true);
        if (stage != Stage.AWAITING_ASSIGNMENT) recovery.setTask(task);
        if (stage == Stage.IN_PROGRESS) {
            var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
            d.startedAt = Instant.now();
            recovery.setDetailsJson(mapper.writeValueAsString(d));
        }
        var managerActions = workflow.get(manager(), recovery.getId()).actions();
        assertThat(workflow.get(business(), recovery.getId()).actions()).containsAll(managerActions);
    }

    @ParameterizedTest
    @EnumSource(value = ViolationRecoveryWorkflow.Action.class, names = {"ASSIGN", "RESUME", "CANCEL"})
    void businessCanExecuteManagerOperationsOnAnEscalatedCase(ViolationRecoveryWorkflow.Action action) throws Exception {
        var original = activateRecovery();
        recovery.setEscalated(true);
        if (action == ViolationRecoveryWorkflow.Action.RESUME) {
            recovery.setTask(task);
            recovery.setStage(Stage.BLOCKED);
            var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
            d.disputeOpen = true;
            recovery.setDetailsJson(mapper.writeValueAsString(d));
        }
        var result = workflow.execute(business(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            action, action == ViolationRecoveryWorkflow.Action.ASSIGN ? staff.getId() : null,
            null, null, null, null, "BO xử lý hồ sơ thay Manager", null, null, null, null, null, null));
        assertThat(result.stage()).isEqualTo(action == ViolationRecoveryWorkflow.Action.CANCEL ? Stage.CANCELLED : Stage.AWAITING_ATTENDANCE);
        assertThat(result.escalated()).isFalse();
        assertThat(result.deadlineAt()).isEqualTo(original.deadlineAt());
        assertThat(result.history().getLast().actorName()).isEqualTo(business.getFullName());
        if (action == ViolationRecoveryWorkflow.Action.ASSIGN) assertThat(recovery.getTask().getAssignedTo()).isEqualTo(staff);
        if (action == ViolationRecoveryWorkflow.Action.RESUME)
            assertThat(mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class).disputeOpen).isFalse();
        if (action == ViolationRecoveryWorkflow.Action.CANCEL) assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
    }

    @Test
    void businessKeepsManagerPickupActionsAndItsOwnLiquidationAction() throws Exception {
        completeWithStoredItems(1);
        workflow.execute(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, Instant.now(),
            null, null, null, null, null, null, null));
        var managerActions = workflow.get(manager(), recovery.getId()).actions();
        assertThat(managerActions).contains(ViolationRecoveryWorkflow.Action.FOLLOW_UP_PICKUP,
            ViolationRecoveryWorkflow.Action.ASSIGN_PICKUP);
        assertThat(workflow.get(business(), recovery.getId()).actions()).containsAll(managerActions)
            .contains(ViolationRecoveryWorkflow.Action.RECORD_LIQUIDATION);
    }

    @Test
    void businessCannotTakeOverBeforeEscalationOrWithoutManagementScope() {
        activateRecovery();
        var assign = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.ASSIGN,
            staff.getId(), null, null, null, null, null, null, null, null, null, null, null);
        assertThat(workflow.get(business(), recovery.getId()).actions()).doesNotContain(
            ViolationRecoveryWorkflow.Action.ASSIGN, ViolationRecoveryWorkflow.Action.RESUME, ViolationRecoveryWorkflow.Action.CANCEL);
        assertThatThrownBy(() -> workflow.execute(business(), recovery.getId(), assign)).isInstanceOf(ApiException.class);
        recovery.setEscalated(true);
        var readOnlyBusiness = actor(business, RoleCode.BUSINESS, null, FacilityScopeLevel.READ);
        assertThat(workflow.get(readOnlyBusiness, recovery.getId()).actions()).doesNotContain(ViolationRecoveryWorkflow.Action.ASSIGN);
        assertThatThrownBy(() -> workflow.execute(readOnlyBusiness, recovery.getId(), assign)).isInstanceOf(ApiException.class);
        assertThat(recovery.getTask()).isNull();
    }

    @Test
    void confirmedRecoveryNotifiesAllEligibleManagersWithOrderAndAssignmentDestination() {
        var anotherManager = identified(new User());
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facility.getId(), List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager, anotherManager));
        activateRecovery();
        for (var recipient : List.of(manager, anotherManager)) {
            verify(notifications).createNotificationOnce(eq(recipient.getId()), eq(NotificationType.RETURN),
                eq("Cần phân công nhân viên thu hồi"), argThat(text -> text.contains("RSV-TEST-10")
                    && text.contains("GA-01") && text.contains("Nhân viên & Nhiệm vụ")), eq(recovery.getId()));
        }
    }

    @Test
    void activationPublishesCustomerNoticeAndStartsReplyWindowWithoutManualConfirmation() {
        var entry = activateRecovery();
        assertThat(entry.notifiedAt()).isEqualTo(entry.requiredAt());
        assertThat(entry.responseDueAt()).isEqualTo(entry.notifiedAt().plus(Duration.ofHours(6)));
        assertThat(recovery.getResponseCheckAt()).isEqualTo(entry.responseDueAt());
        assertThat(entry.actions()).extracting(Enum::name).doesNotContain("NOTICE");
        verify(notifications).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            eq("Thông báo thu hồi kho"), argThat(text -> text.contains("RSV-TEST-10")
                && text.contains("GA-01") && text.contains("Lý do:") && text.contains("Lịch thu hồi:")
                && text.contains("Vui lòng phản hồi trước")), eq(recovery.getId()));
        var customerView = workflow.get(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId());
        assertThat(customerView.actions()).contains(ViolationRecoveryWorkflow.Action.WILL_ATTEND,
            ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, ViolationRecoveryWorkflow.Action.DISPUTE);
    }

    @Test
    void automaticIntakeDetectionAlsoPublishesNoticeWithoutManagerAction() throws Exception {
        var now = Instant.now();
        assertThat(workflow.detect(rental, null, Reason.INVENTORY_NO_SHOW, now.minusSeconds(60), now, false)).isTrue();
        var created = org.mockito.ArgumentCaptor.forClass(ViolationRecovery.class);
        verify(cases).saveAndFlush(created.capture());
        var details = mapper.readValue(created.getValue().getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        assertThat(details.notifiedAt).isEqualTo(now);
        assertThat(details.responseDueAt).isEqualTo(now.plus(Duration.ofHours(6)));
        verify(notifications).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            eq("Thông báo thu hồi kho"), contains("Không đến kiểm kê theo lịch hẹn"), eq(created.getValue().getId()));
    }

    @Test
    void existingAssignedCaseGetsOneAutomaticNoticeAndKeepsItsReplyDeadline() throws Exception {
        recovery.setRequiredAt(Instant.now().minus(Duration.ofHours(2)));
        recovery.setStage(Stage.AWAITING_ACCEPTANCE);
        recovery.setTask(task);
        recovery.setScheduledAt(Instant.now().plus(Duration.ofHours(24)));
        recovery.setDeadlineAt(Instant.now().plus(Duration.ofHours(70)));
        workflow.notifyPendingNotifications(recovery.getId());
        var first = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        workflow.notifyPendingNotifications(recovery.getId());
        var second = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        assertThat(first.notifiedAt).isNotNull();
        assertThat(second.notifiedAt).isEqualTo(first.notifiedAt);
        assertThat(second.responseDueAt).isEqualTo(first.notifiedAt.plus(Duration.ofHours(6)));
        verify(notifications, times(1)).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            eq("Thông báo thu hồi kho"), anyString(), eq(recovery.getId()));
    }

    @Test
    void managerAndBoCannotChangeTheAutomaticScheduleOrResetTheCustomerReplyWindow() {
        var original = activateRecovery();
        var appointment = original.scheduledAt().plusSeconds(3600);
        var change = new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.RESCHEDULE, null, null, null, null, appointment,
            "Đổi lịch theo đề nghị của khách", null, null, null, null, null, null);
        recovery.setEscalated(true);
        for (var actor : List.of(manager(), business())) {
            assertThat(workflow.get(actor, recovery.getId()).actions()).doesNotContain(ViolationRecoveryWorkflow.Action.RESCHEDULE);
            assertThatThrownBy(() -> workflow.execute(actor, recovery.getId(), change)).isInstanceOf(ApiException.class);
        }
        var updated = workflow.get(manager(), recovery.getId());
        assertThat(updated.scheduledAt()).isEqualTo(original.scheduledAt());
        assertThat(updated.notifiedAt()).isEqualTo(original.notifiedAt());
        assertThat(updated.responseDueAt()).isEqualTo(original.responseDueAt());
        assertThat(updated.deadlineAt()).isEqualTo(original.deadlineAt());
        verify(notifications, never()).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            eq("Lịch thu hồi đã thay đổi"), anyString(), any());
    }

    @Test
    void pendingAssignmentBackfillUsesDeduplicatedNotificationsAndSkipsAssignedCases() {
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facility.getId(), List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager));
        recovery.setStage(Stage.AWAITING_ASSIGNMENT);
        workflow.notifyPendingNotifications(recovery.getId());
        workflow.notifyPendingNotifications(recovery.getId());
        recovery.setStage(Stage.AWAITING_ACCEPTANCE);
        workflow.notifyPendingNotifications(recovery.getId());
        verify(notifications, times(2)).createNotificationOnce(eq(manager.getId()), eq(NotificationType.RETURN),
            eq("Cần phân công nhân viên thu hồi"), contains("RSV-TEST-10"), eq(recovery.getId()));
    }

    @Test
    void oldReviewAutomaticallyOpensWithoutManagerApprovalAndKeepsDetectionHistory() throws Exception {
        var detected = Instant.parse("2026-11-26T10:00:00Z");
        var d = new ViolationRecoveryWorkflow.Details();
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("DETECT", "Hệ thống", detected, Stage.REVIEW_REQUIRED, null));
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facility.getId(), List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager));
        when(users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)).thenReturn(List.of(business));
        var entry = activateRecovery();
        assertThat(entry.stage()).isEqualTo(Stage.AWAITING_ASSIGNMENT);
        assertThat(entry.detectedAt()).isEqualTo(detected);
        assertThat(entry.scheduledAt()).isEqualTo(entry.requiredAt().plus(Duration.ofHours(24)));
        assertThat(entry.deadlineAt()).isEqualTo(entry.scheduledAt().plus(Duration.ofHours(48)));
        assertThat(entry.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION,
            ViolationRecoveryWorkflow.Action.APPROVE, ViolationRecoveryWorkflow.Action.ACCEPT);
        for (var recipient : List.of(manager, business))
            verify(notifications).createNotificationOnce(eq(recipient.getId()), eq(NotificationType.RETURN),
                eq("Cần phân công nhân viên thu hồi"), contains("RSV-TEST-10"), eq(recovery.getId()));
    }

    @Test
    void oldReviewIsCancelledIfClearanceWasAlreadyVerifiedInsteadOfOpeningStaleRecovery() {
        source.setWarehouseEmptyVerified(true);
        source.setWarehouseEmptyVerifiedAt(Instant.now());
        workflow.notifyPendingNotifications(recovery.getId());
        assertThat(recovery.getStage()).isEqualTo(Stage.CANCELLED);
        assertThat(recovery.getRequiredAt()).isNull();
        verify(notifications, never()).createNotification(eq(customer.getId()), any(), anyString(), anyString(), any());
    }

    @Test
    void automaticDetectionDoesNotOpenARecoveryForGoodsAlreadyCleared() {
        source.setWarehouseEmptyVerified(true);
        source.setWarehouseEmptyVerifiedAt(Instant.now());
        assertThat(workflow.detect(rental, source, Reason.REMOVAL_OVERDUE,
            rental.getInventoryRemovalDeadlineAt(), Instant.now(), true)).isFalse();
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void assignmentMakesTheTaskActionableWithoutAcceptOrApproveAndCannotStartBeforeTheSchedule() {
        activateRecovery();
        var assigned = workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN, staff.getId(), null, null, null, null, null, null, null, null, null, null, null));
        assertThat(assigned.stage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        var staffView = workflow.get(staff(), recovery.getId());
        assertThat(staffView.actions())
            .doesNotContain(ViolationRecoveryWorkflow.Action.ACCEPT, ViolationRecoveryWorkflow.Action.APPROVE,
                ViolationRecoveryWorkflow.Action.START, ViolationRecoveryWorkflow.Action.RECORD_ABSENT,
                ViolationRecoveryWorkflow.Action.RECORD_PRESENT, ViolationRecoveryWorkflow.Action.BLOCK);
        assertThatThrownBy(() -> execute(staff(), ViolationRecoveryWorkflow.Action.ACCEPT, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> execute(manager(), ViolationRecoveryWorkflow.Action.APPROVE, "Không cần duyệt", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void customerResponseClosesAtSixHoursAndExpiryDoesNotBlockTheScheduledRecovery() throws Exception {
        activateRecovery();
        recovery.setTask(task);
        recovery.setStage(Stage.AWAITING_ATTENDANCE);
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.responseDueAt = Instant.now().minusSeconds(1);
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        assertThat(workflow.get(customerActor, recovery.getId()).actions()).doesNotContain(
            ViolationRecoveryWorkflow.Action.WILL_ATTEND, ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, ViolationRecoveryWorkflow.Action.DISPUTE);
        assertThatThrownBy(() -> execute(customerActor, ViolationRecoveryWorkflow.Action.WILL_ATTEND, null, null)).isInstanceOf(ApiException.class);
        workflow.processDue(recovery.getId(), Instant.now());
        assertThat(recovery.getStage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(workflow.get(customerActor, recovery.getId()).reply()).isEqualTo(Reply.NO_RESPONSE);
    }

    @Test
    void customerMustWaitForManagerApprovalBeforeChangingAttendanceReply() throws Exception {
        activateRecovery();
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);

        var afterAttend = execute(customerActor, ViolationRecoveryWorkflow.Action.WILL_ATTEND, "Tôi sẽ đến đúng giờ", null);
        assertThat(afterAttend.reply()).isEqualTo(Reply.WILL_ATTEND);
        assertThat(afterAttend.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.WILL_ATTEND)
            .doesNotContain(ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, ViolationRecoveryWorkflow.Action.DISPUTE);
        assertThatThrownBy(() -> execute(customerActor, ViolationRecoveryWorkflow.Action.WILL_ATTEND, null, null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Thao tác chưa đủ điều kiện");

        execute(manager(), ViolationRecoveryWorkflow.Action.RESUME, "Đã duyệt phản hồi", null);
        var afterCannotAttend = execute(customerActor, ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, "Có việc đột xuất", null);
        assertThat(afterCannotAttend.reply()).isEqualTo(Reply.CANNOT_ATTEND);
        assertThat(afterCannotAttend.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.CANNOT_ATTEND)
            .doesNotContain(ViolationRecoveryWorkflow.Action.WILL_ATTEND, ViolationRecoveryWorkflow.Action.DISPUTE);
        assertThatThrownBy(() -> execute(customerActor, ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, null, null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Thao tác chưa đủ điều kiện");

        execute(manager(), ViolationRecoveryWorkflow.Action.RESUME, "Đã duyệt phản hồi", null);
        var toggledBack = execute(customerActor, ViolationRecoveryWorkflow.Action.WILL_ATTEND, "Đã sắp xếp lại được", null);
        assertThat(toggledBack.reply()).isEqualTo(Reply.WILL_ATTEND);
        assertThat(toggledBack.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.WILL_ATTEND)
            .doesNotContain(ViolationRecoveryWorkflow.Action.CANNOT_ATTEND, ViolationRecoveryWorkflow.Action.DISPUTE);
    }

    @Test
    void customerDisputeAndManagerResumePreserveMultipleAttachmentsAndNotifyCustomer() throws Exception {
        activateRecovery();
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        var managerActor = manager();

        var customerFile1 = UUID.randomUUID();
        var customerFile2 = UUID.randomUUID();
        for (var fileId : List.of(customerFile1, customerFile2)) {
            var asset = new FileAsset();
            ReflectionTestUtils.setField(asset, "id", fileId);
            asset.setStatus(FileAssetStatus.ACTIVE);
            asset.setEntityType("VIOLATION_RECOVERY");
            asset.setEntityId(recovery.getId());
            asset.setUploadedBy(customer);
            when(files.findById(fileId)).thenReturn(Optional.of(asset));
        }

        var disputed = workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.DISPUTE, null, null, null, null, null,
            "Tôi đã báo chuẩn bị xong trước hạn", null, null, null, null, null, null, null, null,
            List.of(customerFile1, customerFile2)
        ));
        assertThat(disputed.customerAttachmentIds()).containsExactly(customerFile1, customerFile2);

        var managerFile1 = UUID.randomUUID();
        var managerFile2 = UUID.randomUUID();
        for (var fileId : List.of(managerFile1, managerFile2)) {
            var asset = new FileAsset();
            ReflectionTestUtils.setField(asset, "id", fileId);
            asset.setStatus(FileAssetStatus.ACTIVE);
            asset.setEntityType("VIOLATION_RECOVERY");
            asset.setEntityId(recovery.getId());
            asset.setUploadedBy(manager);
            when(files.findById(fileId)).thenReturn(Optional.of(asset));
        }

        workflow.execute(managerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.RESUME, null, null, null, null, null,
            "Đã kiểm tra camera, vẫn tiếp tục thu hồi theo lịch", null, null, null, null, null, null, null, null,
            List.of(managerFile1, managerFile2)
        ));

        var customerView = workflow.get(customerActor, recovery.getId());
        assertThat(customerView.managerResponseNote()).isEqualTo("Đã kiểm tra camera, vẫn tiếp tục thu hồi theo lịch");
        assertThat(customerView.managerRespondedAt()).isNotNull();
        assertThat(customerView.customerAttachmentIds()).containsExactly(customerFile1, customerFile2);
        assertThat(customerView.managerAttachmentIds()).containsExactly(managerFile1, managerFile2);
        verify(notifications).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            contains("Quản lý đã phản hồi"), contains("Đã kiểm tra camera"), eq(recovery.getId()));

        var secondDispute = workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.DISPUTE, null, null, null, null, null,
            "Khiếu nại lần 2: tôi có biên nhận bàn giao", null, null, null, null, null, null, null, null, null
        ));
        assertThat(secondDispute.communications()).hasSize(3);
        assertThat(secondDispute.communications().get(0).note()).isEqualTo("Tôi đã báo chuẩn bị xong trước hạn");
        assertThat(secondDispute.communications().get(1).note()).isEqualTo("Đã kiểm tra camera, vẫn tiếp tục thu hồi theo lịch");
        assertThat(secondDispute.communications().get(2).note()).isEqualTo("Khiếu nại lần 2: tôi có biên nhận bàn giao");
    }

    @Test
    void schedulerDoesNotUnassignOrPauseStaffWhenTheOldAcceptanceOrAttendanceDeadlineExpires() throws Exception {
        readyForAbsentHandover();
        recovery.setActionDueAt(Instant.now().minusSeconds(1));
        workflow.processDue(recovery.getId(), Instant.now());
        assertThat(recovery.getStage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(task.getAssignedTo()).isEqualTo(staff);
        assertThat(workflow.get(staff(), recovery.getId()).actions()).contains(ViolationRecoveryWorkflow.Action.START);
    }

    @Test
    void staffListAndAssignmentBothRejectAnOverlappingOrder() {
        activateRecovery();
        when(staffAvailability.available(eq(staff.getId()), eq(recovery), any(), any())).thenReturn(false);
        assertThat(workflow.staff(manager(), recovery.getId(), false, false))
            .extracting(ViolationRecoveryWorkflow.StaffOption::id).containsExactly(witness.getId());
        assertThatThrownBy(() -> workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.ASSIGN, staff.getId(), null, null, null, null, null, null, null, null, null, null, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("lịch xử lý đơn khác");
        assertThat(recovery.getTask()).isNull();
        verify(staffAvailability).lock(Collections.singletonList(staff.getId()));
    }

    @Test
    void staffWithoutReturnProcessingPermissionIsNotOffered() {
        activateRecovery();
        witness.getRoles().clear();
        assertThat(workflow.staff(manager(), recovery.getId(), false, false))
            .extracting(ViolationRecoveryWorkflow.StaffOption::id).containsExactly(staff.getId());
    }
    @Test
    void absentCustomerCanBeRecoveredAfterAttendanceRecordWithoutApproval() throws Exception {
        readyForAbsentHandover();
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.attendance = null;
        d.acceptedAt = null;
        d.approvedAt = null;
        d.approvedBy = null;
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        var recorded = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.RECORD_ABSENT,
            null, null, photo(), null, null));
        assertThat(recorded.stage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(recorded.needsWitness()).isTrue();
        var customerView = workflow.get(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId());
        assertThat(customerView.attendance()).isEqualTo(Attendance.ABSENT);
        assertThat(customerView.attendanceEvidenceId()).isNull();
        assertThat(recorded.actions()).contains(ViolationRecoveryWorkflow.Action.START)
            .doesNotContain(ViolationRecoveryWorkflow.Action.ACCEPT, ViolationRecoveryWorkflow.Action.APPROVE);
        assertThat(workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null)).stage()).isEqualTo(Stage.IN_PROGRESS);
    }

    @Test
    void customerPickupCannotBeScheduledInThePastOrAfterTheExactStorageDeadline() throws Exception {
        completeWithStoredItems(1);
        var customerActor = actor(customer, RoleCode.CUSTOMER, null, null);
        var originalDetails = recovery.getDetailsJson();
        for (var appointment : List.of(Instant.now().minusSeconds(120), recovery.getPickupDueAt().plusSeconds(1))) {
            assertThatThrownBy(() -> workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
                ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, appointment,
                null, null, null, null, null, null, null))).isInstanceOf(ApiException.class);
            assertThat(recovery.getDetailsJson()).isEqualTo(originalDetails);
        }
        var atDeadline = workflow.execute(customerActor, recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP, null, null, null, null, recovery.getPickupDueAt(),
            null, null, null, null, null, null, null));
        assertThat(atDeadline.pickupAppointmentAt()).isEqualTo(recovery.getPickupDueAt());
        recovery.setPickupDueAt(Instant.now().minusSeconds(1));
        assertThat(workflow.get(customerActor, recovery.getId()).actions()).doesNotContain(ViolationRecoveryWorkflow.Action.SCHEDULE_PICKUP);
    }

    @Test
    void failedIdentityShowsEachResultButRecoveryContinuesWithTemporaryStorage() throws Exception {
        readyForPresentHandover();
        var first = workflow.execute(staff(), recovery.getId(), identityAttempt("WRONG123", UUID.randomUUID()));
        assertThat(first.verification().used()).isEqualTo(1);
        assertThat(first.verification().remaining()).isEqualTo(1);
        assertThat(first.verification().message()).contains("không khớp", "lần 1/2", "Còn 1");
        assertThat(first.actions()).contains(ViolationRecoveryWorkflow.Action.VERIFY, ViolationRecoveryWorkflow.Action.START);
        var lastAttempt = identityAttempt("WRONG456", UUID.randomUUID());
        var failed = workflow.execute(staff(), recovery.getId(), lastAttempt);
        assertThat(failed.stage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(failed.verification().used()).isEqualTo(2);
        assertThat(failed.verification().remaining()).isZero();
        assertThat(failed.verification().canReceiveItems()).isFalse();
        assertThat(failed.verification().message()).contains("2/2", "Đã hết lượt", "Thu hồi vẫn tiếp tục");
        var customerView = workflow.get(actor(customer, RoleCode.CUSTOMER, null, null), recovery.getId());
        assertThat(customerView.verification().used()).isEqualTo(2);
        assertThat(customerView.verification().matched()).isFalse();
        assertThat(customerView.verification().message()).contains("2/2");
        assertThat(customerView.attendanceEvidenceId()).isNull();
        assertThat(customerView.history()).isEmpty();
        assertThat(mapper.writeValueAsString(customerView)).doesNotContain("TEST99999", "WRONG123", "WRONG456");
        assertThat(failed.needsWitness()).isTrue();
        assertThat(failed.actions()).contains(ViolationRecoveryWorkflow.Action.START)
            .doesNotContain(ViolationRecoveryWorkflow.Action.VERIFY, ViolationRecoveryWorkflow.Action.RECORD_PRESENT);
        assertThat(workflow.execute(staff(), recovery.getId(), lastAttempt).verification().used()).isEqualTo(2);
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), identityAttempt("TEST99999", UUID.randomUUID())))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, null, photo(), "TEMP-A", null))).isInstanceOf(ApiException.class);
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        assertThat(task.getRecoveryWitness()).isEqualTo(witness);
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), parcel("HANDED_OVER")))
            .isInstanceOf(ApiException.class).hasMessageContaining("xác minh quyền nhận");
        assertThat(workflow.execute(staff(), recovery.getId(), parcel("STORED")).items()).hasSize(1);
        assertThat(workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian trống, hàng đã lưu tạm", null, photo(), null, "VACATED")).stage()).isEqualTo(Stage.COMPLETED);
    }

    @Test
    void newArrivalAndManagerResumeDoNotRestoreConsumedIdentityAttempts() throws Exception {
        readyForPresentHandover();
        workflow.execute(staff(), recovery.getId(), identityAttempt("WRONG123", UUID.randomUUID()));
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.arrivedAt = Instant.now().minus(Duration.ofMinutes(31));
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        var rerecorded = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.RECORD_PRESENT,
            null, null, photo(), null, null));
        assertThat(rerecorded.verificationAttempts()).isEqualTo(1);
        workflow.execute(staff(), recovery.getId(), identityAttempt("WRONG456", UUID.randomUUID()));
        pauseForReview("Phát hiện hàng nguy hiểm");
        workflow.processDue(recovery.getId(), Instant.now());
        assertThat(recovery.getStage()).isEqualTo(Stage.BLOCKED);
        execute(manager(), ViolationRecoveryWorkflow.Action.RESUME, "Đã xử lý an toàn", null);
        var resumed = workflow.get(staff(), recovery.getId());
        assertThat(resumed.verificationAttempts()).isEqualTo(2);
        assertThat(resumed.actions()).contains(ViolationRecoveryWorkflow.Action.START);
        assertThat(resumed.actions()).doesNotContain(ViolationRecoveryWorkflow.Action.VERIFY, ViolationRecoveryWorkflow.Action.RECORD_PRESENT);
    }

    @Test
    void missingIdentityRecordStillAllowsWitnessedStorageWithoutOfferingVerification() throws Exception {
        readyForPresentHandover();
        rental.getReservation().setIdentityDocumentNumber(null);
        var entry = workflow.get(staff(), recovery.getId());
        assertThat(entry.verification().message()).contains("thiếu giấy tờ");
        assertThat(entry.actions()).contains(ViolationRecoveryWorkflow.Action.START)
            .doesNotContain(ViolationRecoveryWorkflow.Action.VERIFY);
        var started = workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        assertThat(started.stage()).isEqualTo(Stage.IN_PROGRESS);
        assertThat(workflow.execute(staff(), recovery.getId(), parcel("STORED")).items()).hasSize(1);
    }

    @Test
    void legacyIdentityOnlyPauseIsRemovedWithoutResettingEvidenceScheduleOrAttempts() throws Exception {
        readyForPresentHandover();
        recovery.setPolicyVersion("RECOVERY_V2");
        recovery.setStage(Stage.BLOCKED);
        var scheduled = recovery.getScheduledAt();
        var deadline = recovery.getDeadlineAt();
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.attempts.addAll(List.of(UUID.randomUUID(), UUID.randomUUID()));
        d.reasonNote = "Đã hết số lượt xác minh danh tính.";
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("VERIFY", "Nhân viên", Instant.now(), Stage.BLOCKED, ""));
        var evidence = d.attendanceEvidenceId;
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        workflow.notifyPendingNotifications(recovery.getId());
        var migrated = workflow.get(staff(), recovery.getId());
        assertThat(migrated.stage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(migrated.verificationAttempts()).isEqualTo(2);
        assertThat(migrated.attendanceEvidenceId()).isEqualTo(evidence);
        assertThat(migrated.scheduledAt()).isEqualTo(scheduled);
        assertThat(migrated.deadlineAt()).isEqualTo(deadline);
        assertThat(migrated.needsWitness()).isTrue();
        assertThat(migrated.history().getLast().action()).isEqualTo("IDENTITY_STORAGE");
        workflow.notifyPendingNotifications(recovery.getId());
        assertThat(workflow.get(staff(), recovery.getId()).history()).hasSize(migrated.history().size());
    }

    @Test
    void identityMigrationKeepsCustomerDisputesAndExplicitStaffPausesBlocked() throws Exception {
        readyForPresentHandover();
        recovery.setPolicyVersion("RECOVERY_V2");
        recovery.setStage(Stage.BLOCKED);
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.attempts.addAll(List.of(UUID.randomUUID(), UUID.randomUUID()));
        d.reasonNote = "Đã hết số lượt xác minh danh tính.";
        d.disputeOpen = true;
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("VERIFY", "Nhân viên", Instant.now(), Stage.BLOCKED, ""));
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        workflow.notifyPendingNotifications(recovery.getId());
        assertThat(recovery.getStage()).isEqualTo(Stage.BLOCKED);
        d.disputeOpen = false;
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("BLOCK", "Nhân viên", Instant.now(), Stage.BLOCKED, ""));
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        workflow.notifyPendingNotifications(recovery.getId());
        assertThat(recovery.getStage()).isEqualTo(Stage.BLOCKED);
    }

    @Test
    void presentCustomerCanBeRecoveredAfterIdentityVerificationWithoutApproval() throws Exception {
        readyForAbsentHandover();
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.attendance = null;
        d.needsWitness = false;
        d.acceptedAt = null;
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.RECORD_PRESENT,
            null, null, photo(), null, null));
        var verified = workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.VERIFY, null, null, null, UUID.randomUUID(), null,
            null, "TEST99999", null, null, null, null, null));
        assertThat(verified.stage()).isEqualTo(Stage.AWAITING_ATTENDANCE);
        assertThat(verified.verification().matched()).isTrue();
        assertThat(verified.verification().canReceiveItems()).isTrue();
        assertThat(verified.verification().message()).contains("Xác minh thành công");
        assertThat(verified.actions()).contains(ViolationRecoveryWorkflow.Action.START);
        assertThat(workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, null, photo(), "Khách nhận trực tiếp", null)).stage()).isEqualTo(Stage.IN_PROGRESS);
        assertThat(workflow.execute(staff(), recovery.getId(), parcel("HANDED_OVER")).items().getFirst().disposition())
            .isEqualTo("HANDED_OVER");
    }

    @Test
    void startIsAvailableAtThe24HourMarkAndCompletionIsOnTimeAtThe72HourMark() throws Exception {
        readyForAbsentHandover();
        recovery.setPolicyVersion("RECOVERY_V2");
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        List<ViolationRecoveryWorkflow.Action> early = ReflectionTestUtils.invokeMethod(workflow, "allowed",
            staff(), recovery, d, recovery.getScheduledAt().minusNanos(1));
        List<ViolationRecoveryWorkflow.Action> due = ReflectionTestUtils.invokeMethod(workflow, "allowed",
            staff(), recovery, d, recovery.getScheduledAt());
        assertThat(early).doesNotContain(ViolationRecoveryWorkflow.Action.START);
        assertThat(due).contains(ViolationRecoveryWorkflow.Action.START);
        workflow.processDue(recovery.getId(), recovery.getDeadlineAt());
        assertThat(recovery.getOverdueAt()).isNull();
        workflow.processDue(recovery.getId(), recovery.getDeadlineAt().plusNanos(1));
        assertThat(recovery.getOverdueAt()).isEqualTo(recovery.getDeadlineAt());
    }

    @Test
    void coordinationNoticeDoesNotReachBoScopedToAnotherFacility() {
        var otherFacility = identified(new Facility());
        var scope = new UserFacilityScope();
        scope.setFacility(otherFacility);
        scope.setScopeLevel(FacilityScopeLevel.MANAGE);
        when(users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)).thenReturn(List.of(business));
        when(userFacilityScopes.findByUserId(business.getId())).thenReturn(List.of(scope));
        workflow.notifyPendingNotifications(recovery.getId());
        verify(notifications, never()).createNotificationOnce(eq(business.getId()), any(), anyString(), anyString(), any());
    }

    private void completeWithStoredItems(int count) throws Exception {
        readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        for (int index = 1; index <= count; index++) {
            workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
                ViolationRecoveryWorkflow.Action.SAVE_ITEM, null, null, photo(), null, null,
                null, null, "PKG-" + index, "Kiện " + index, "Nguyên vẹn", "TEMP-A", "STORED"));
        }
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian trống, đã kiểm tra khóa", null, photo(), null, "VACATED"));
    }

    private ViolationRecoveryWorkflow.Command liquidation(String code, UUID evidenceId, String note, String confirmation) {
        return new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RECORD_LIQUIDATION,
            null, null, evidenceId, null, null, note, null, code, null, null, null, confirmation);
    }

    @Test
    void bulkRecordingAndPartialPickupKeepTheRemainingQuantityAndRejectDuplicateRelease() throws Exception {
        readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        var save = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
            null, null, photo(), UUID.randomUUID(), null, null, null, null, "Thùng tài liệu", "Nguyên vẹn", "TEMP-A", "STORED", 100, null);
        var recorded = workflow.execute(staff(), recovery.getId(), save);
        assertThat(recorded.items()).hasSize(100);
        assertThat(workflow.execute(staff(), recovery.getId(), save).items()).hasSize(100);
        assertThat(recorded.items()).extracting(ViolationRecoveryWorkflow.Item::code).doesNotHaveDuplicates();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.COMPLETE,
            "Gian trống; đã lưu tạm 100 kiện", null, photo(), null, "VACATED"));
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.pickupOpenedAt = Instant.now();
        d.pickupVerifiedAt = Instant.now();
        d.pickupStaffId = staff.getId();
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        var firstThirty = recorded.items().stream().limit(30).map(ViolationRecoveryWorkflow.Item::code).toList();
        var release = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RELEASE_ITEM,
            null, null, photo(), null, null, null, null, null, null, null, null, null, null, firstThirty);
        var received = workflow.execute(staff(), recovery.getId(), release);
        assertThat(received.items().stream().filter(item -> item.releasedAt() != null)).hasSize(30);
        assertThat(received.items().stream().filter(item -> item.releasedAt() == null && item.liquidation() == null)).hasSize(70);
        String unchanged = recovery.getDetailsJson();
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), release)).isInstanceOf(ApiException.class);
        var mixed = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RELEASE_ITEM,
            null, null, photo(), null, null, null, null, null, null, null, null, null, null,
            List.of(recorded.items().get(30).code(), firstThirty.getFirst()));
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), mixed)).isInstanceOf(ApiException.class);
        assertThat(recovery.getDetailsJson()).isEqualTo(unchanged);
        var liquidation = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.RECORD_LIQUIDATION,
            null, null, photo(business), null, null, "Đã xử lý theo biên bản", null, null, null, null, null, "LIQUIDATED", null,
            recorded.items().subList(30, 50).stream().map(ViolationRecoveryWorkflow.Item::code).toList());
        var disposed = workflow.execute(business(), recovery.getId(), liquidation);
        assertThat(disposed.items().stream().filter(item -> item.liquidation() != null)).hasSize(20);
        assertThat(disposed.items().stream().filter(item -> item.releasedAt() == null && item.liquidation() == null)).hasSize(50);
    }

    @Test
    void bulkRecordingValidatesQuantityAndAllCodesBeforeChangingAnyItem() throws Exception {
        readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        for (int quantity : List.of(0, -1, 1001)) {
            var invalid = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
                null, null, photo(), null, null, null, null, "BATCH", "Tài liệu", "Nguyên vẹn", "TEMP-A", "STORED", quantity, null);
            assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), invalid)).isInstanceOf(ApiException.class);
        }
        workflow.execute(staff(), recovery.getId(), new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
            null, null, photo(), null, null, null, null, "BATCH-2", "Tài liệu", "Nguyên vẹn", "TEMP-A", "STORED"));
        var conflict = new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
            null, null, photo(), null, null, null, null, "BATCH", "Tài liệu", "Nguyên vẹn", "TEMP-A", "STORED", 3, null);
        assertThatThrownBy(() -> workflow.execute(staff(), recovery.getId(), conflict)).isInstanceOf(ApiException.class);
        assertThat(workflow.get(staff(), recovery.getId()).items()).extracting(ViolationRecoveryWorkflow.Item::code).containsExactly("BATCH-2");
    }

    private void pauseForReview(String note) throws Exception {
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.reasonNote = note;
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("BLOCK", "Nhân viên", Instant.now(), Stage.BLOCKED, note));
        recovery.setStage(Stage.BLOCKED);
        recovery.setDetailsJson(mapper.writeValueAsString(d));
    }

    private void readyForPresentHandover() throws Exception {
        readyForAbsentHandover();
        var d = mapper.readValue(recovery.getDetailsJson(), ViolationRecoveryWorkflow.Details.class);
        d.attendance = null;
        d.needsWitness = false;
        recovery.setDetailsJson(mapper.writeValueAsString(d));
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.RECORD_PRESENT,
            null, null, photo(), null, null));
    }

    private ViolationRecoveryWorkflow.Command identityAttempt(String document, UUID attemptId) {
        return new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.VERIFY,
            null, null, null, attemptId, null, null, document, null, null, null, null, null);
    }

    private ViolationRecoveryWorkflow.Command parcel(String disposition) {
        return new ViolationRecoveryWorkflow.Command(ViolationRecoveryWorkflow.Action.SAVE_ITEM,
            null, null, photo(), null, null, null, null, "PKG-1", "Kiện hàng", "Nguyên vẹn", "TEMP-A", disposition);
    }

    private Instant readyForAbsentHandover() throws Exception {
        var now = Instant.now();
        recovery.setTask(task);
        recovery.setStage(Stage.AWAITING_ATTENDANCE);
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

    private ViolationRecoveryWorkflow.Entry activateRecovery() {
        workflow.notifyPendingNotifications(recovery.getId());
        return workflow.get(manager(), recovery.getId());
    }

    private UUID photo() {
        return photo(staff);
    }

    private UUID photo(User uploader) {
        var file = identified(new FileAsset());
        file.setStatus(FileAssetStatus.ACTIVE);
        file.setEntityType("MAINTENANCE_TASK");
        file.setEntityId(task.getId());
        file.setUploadedBy(uploader);
        file.setContentType("image/jpeg");
        when(files.findById(file.getId())).thenReturn(Optional.of(file));
        return file.getId();
    }

    private ActorPrincipal manager() { return actor(manager, RoleCode.MANAGER, SystemPermission.MANAGE_STAFF_TASKS, FacilityScopeLevel.MANAGE); }
    private ActorPrincipal staff() { return actor(staff, RoleCode.STAFF, SystemPermission.PROCESS_RETURNS, FacilityScopeLevel.OPERATE); }
    private ActorPrincipal business() { return actor(business, RoleCode.BUSINESS, null, FacilityScopeLevel.MANAGE); }

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
