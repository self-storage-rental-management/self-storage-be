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
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ViolationRecoveryWorkflow workflow = new ViolationRecoveryWorkflow(cases, rentals, returns, tasks, users,
        files, access, mock(UnitAssignmentRepository.class), notifications, mock(AuditLogService.class),
        new FacilityScopeService(), new WorkflowBusinessCalendar(mock(SystemSettingRepository.class), mapper),
        mock(EntityManager.class), mapper, new RecoveryReviewService(checkIns, returns, files, mapper), userFacilityScopes);
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

    @ParameterizedTest
    @EnumSource(value = ViolationRecoveryWorkflow.Action.class, names = {"BLOCK", "DISPUTE"})
    void pausePreventsAddingItemsAndCompletionUntilManagerResumesAndApproves(
        ViolationRecoveryWorkflow.Action pauseAction
    ) throws Exception {
        var deadline = readyForAbsentHandover();
        workflow.execute(staff(), recovery.getId(), command(ViolationRecoveryWorkflow.Action.START,
            null, witness.getId(), photo(), "TEMP-A", null));
        var firstItem = new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.SAVE_ITEM, null, null, photo(), null, null,
            null, null, "PKG-1", "Kiện đã lưu", "Nguyên vẹn", "TEMP-A", "STORED");
        var saved = workflow.execute(staff(), recovery.getId(), firstItem);
        var pausingActor = pauseAction == ViolationRecoveryWorkflow.Action.BLOCK ? staff()
            : actor(customer, RoleCode.CUSTOMER, null, null);
        execute(pausingActor, pauseAction, "Cần quản lý rà soát", null);

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
        execute(manager(), ViolationRecoveryWorkflow.Action.APPROVE, "Đủ điều kiện thu hồi", witness.getId());
        execute(staff(), ViolationRecoveryWorkflow.Action.START, null, witness.getId());
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
    void confirmedRecoveryNotifiesAllEligibleManagersWithOrderAndAssignmentDestination() {
        var anotherManager = identified(new User());
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facility.getId(), List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager, anotherManager));
        execute(manager(), ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION, "Đủ căn cứ", null);
        for (var recipient : List.of(manager, anotherManager)) {
            verify(notifications).createNotificationOnce(eq(recipient.getId()), eq(NotificationType.RETURN),
                eq("Cần phân công nhân viên thu hồi"), argThat(text -> text.contains("RSV-TEST-10")
                    && text.contains("GA-01") && text.contains("Nhân viên & Nhiệm vụ")), eq(recovery.getId()));
        }
    }

    @Test
    void activationPublishesCustomerNoticeAndStartsReplyWindowWithoutManualConfirmation() {
        var entry = execute(manager(), ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION, "Đủ căn cứ", null);
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
        when(cases.saveAndFlush(any())).thenAnswer(call -> identified(call.getArgument(0)));
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
    void changingAppointmentAutomaticallyNotifiesCustomerAndOpensANewReplyWindow() {
        var original = execute(manager(), ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION, "Đủ căn cứ", null);
        var appointment = original.scheduledAt().atZone(com.storagehub.time.BusinessTime.ZONE)
            .withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant();
        if (appointment.equals(original.scheduledAt())) appointment = appointment.plusSeconds(3600);
        var updated = workflow.execute(manager(), recovery.getId(), new ViolationRecoveryWorkflow.Command(
            ViolationRecoveryWorkflow.Action.RESCHEDULE, null, null, null, null, appointment,
            "Đổi lịch theo đề nghị của khách", null, null, null, null, null, null));
        assertThat(updated.notifiedAt()).isAfterOrEqualTo(original.notifiedAt());
        assertThat(updated.responseDueAt()).isEqualTo(updated.notifiedAt().plus(Duration.ofHours(6)));
        assertThat(updated.deadlineAt()).isEqualTo(original.deadlineAt());
        verify(notifications).createNotification(eq(customer.getId()), eq(NotificationType.RETURN),
            eq("Lịch thu hồi đã thay đổi"), contains("Vui lòng phản hồi trước"), eq(recovery.getId()));
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
    void reviewExpiryAlertsBoWithoutOpeningRecoveryOrStartingThe72HourClock() {
        var detected = Instant.parse("2026-11-26T10:00:00Z");
        recovery.setActionDueAt(detected.plus(Duration.ofHours(1)));
        var d = new ViolationRecoveryWorkflow.Details();
        d.history.add(new ViolationRecoveryWorkflow.HistoryEvent("DETECT", "Hệ thống", detected, Stage.REVIEW_REQUIRED, null));
        try { recovery.setDetailsJson(mapper.writeValueAsString(d)); } catch (Exception ex) { throw new AssertionError(ex); }
        when(users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)).thenReturn(List.of(business));
        var deadline = recovery.getActionDueAt();
        workflow.processDue(recovery.getId(), deadline.minusNanos(1));
        assertThat(recovery.isEscalated()).isFalse();
        workflow.processDue(recovery.getId(), deadline);
        var escalated = workflow.get(business(), recovery.getId());
        assertThat(escalated.stage()).isEqualTo(Stage.REVIEW_REQUIRED);
        assertThat(escalated.detectedAt()).isEqualTo(detected);
        assertThat(escalated.reviewDueAt()).isEqualTo(deadline);
        assertThat(escalated.businessReviewDueAt()).isEqualTo(deadline.plus(Duration.ofHours(4)));
        assertThat(escalated.actions()).contains(ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION,
            ViolationRecoveryWorkflow.Action.CANCEL).doesNotContain(ViolationRecoveryWorkflow.Action.RESUME);
        assertThat(escalated.requiredAt()).isNull();
        assertThat(escalated.deadlineAt()).isNull();
        assertThat(escalated.taskId()).isNull();
        verify(notifications).createNotificationOnce(eq(business.getId()), eq(NotificationType.RETURN),
            eq("Quá hạn duyệt mở thu hồi, cần BO xử lý"), contains("RSV-TEST-10"), eq(recovery.getId()));
        workflow.processDue(recovery.getId(), escalated.businessReviewDueAt());
        var overdue = workflow.get(business(), recovery.getId());
        assertThat(overdue.reviewDueAt()).isEqualTo(deadline);
        assertThat(overdue.businessReviewDueAt()).isEqualTo(escalated.businessReviewDueAt());
        assertThat(overdue.actionDueAt()).isNull();
        assertThat(overdue.requiredAt()).isNull();
        assertThat(overdue.history()).anyMatch(event -> "BO_REVIEW_OVERDUE".equals(event.action()));
        verify(notifications).createNotificationOnce(eq(business.getId()), eq(NotificationType.RETURN),
            eq("BO chưa xử lý hồ sơ mở thu hồi đúng hạn"), contains("RSV-TEST-10"), eq(recovery.getId()));
        workflow.processDue(recovery.getId(), escalated.businessReviewDueAt().plusSeconds(3600));
        verify(notifications, times(1)).createNotificationOnce(eq(business.getId()), eq(NotificationType.RETURN),
            eq("BO chưa xử lý hồ sơ mở thu hồi đúng hạn"), anyString(), eq(recovery.getId()));
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
        verify(notifications, never()).createNotification(eq(customer.getId()), any(), anyString(), anyString(), any());
    }

    @Test
    void managerCanDeclineOpeningWithoutActivatingOrNotifyingCustomer() {
        var entry = execute(manager(), ViolationRecoveryWorkflow.Action.CANCEL, "Khách đã khắc phục, không mở thu hồi", null);
        assertThat(entry.stage()).isEqualTo(Stage.CANCELLED);
        assertThat(entry.requiredAt()).isNull();
        assertThat(entry.deadlineAt()).isNull();
        assertThat(entry.taskId()).isNull();
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
        verify(notifications, never()).createNotification(eq(customer.getId()), any(), anyString(), anyString(), any());
    }

    @Test
    void clearanceRecordedWhileManagerReviewsPreventsOpeningAtSubmitTime() {
        assertThat(workflow.get(manager(), recovery.getId()).review().openingAllowed()).isTrue();
        source.setWarehouseEmptyVerified(true);
        source.setWarehouseEmptyVerifiedAt(Instant.now());
        assertThatThrownBy(() -> execute(manager(), ViolationRecoveryWorkflow.Action.CONFIRM_VIOLATION, "Đã kiểm tra", null))
            .isInstanceOf(ApiException.class).hasMessageContaining("kho trống");
        assertThat(recovery.getRequiredAt()).isNull();
        assertThat(recovery.getDeadlineAt()).isNull();
        verify(tasks, never()).saveAndFlush(any());
    }

    @Test
    void pendingReviewNotifiesBothManagerAndBoWithTheOrderAndNextStepBeforeApproval() {
        when(users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER, facility.getId(), List.of(FacilityScopeLevel.MANAGE)))
            .thenReturn(List.of(manager));
        when(users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)).thenReturn(List.of(business));
        workflow.notifyPendingNotifications(recovery.getId());
        for (var recipient : List.of(manager, business)) {
            verify(notifications).createNotificationOnce(eq(recipient.getId()), eq(NotificationType.RETURN),
                eq("Cần duyệt mở thu hồi"), argThat(text -> text.contains("RSV-TEST-10") && text.contains("GA-01")
                    && text.contains("biên bản") && text.contains("hạn dọn hàng")), eq(recovery.getId()));
        }
        assertThat(recovery.getRequiredAt()).isNull();
        verify(notifications, never()).createNotification(eq(customer.getId()), any(), anyString(), anyString(), any());
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
