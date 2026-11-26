package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MismatchReturnWorkflowTests {

    RentalRepository rentalRepository = mock(RentalRepository.class);
    ReturnCaseRepository returnCaseRepository = mock(ReturnCaseRepository.class);
    StorageUnitRepository storageUnitRepository = mock(StorageUnitRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    AdminAuthorizationService authorizationService = mock(AdminAuthorizationService.class);
    FacilityScopeService facilityScopeService = mock(FacilityScopeService.class);
    NotificationService notificationService = mock(NotificationService.class);
    AuditLogService auditLogService = mock(AuditLogService.class);
    MaintenanceTaskRepository maintenanceTaskRepository = mock(MaintenanceTaskRepository.class);
    UserFacilityScopeRepository userFacilityScopeRepository = mock(UserFacilityScopeRepository.class);
    EntityManager entityManager = mock(EntityManager.class);

    ReturnSettlementService service;

    UUID facilityId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    UUID managerId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID rentalId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();

    ActorPrincipal customerActor = new ActorPrincipal(customerId, UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Collections.emptySet(), Collections.emptyMap());
    ActorPrincipal staffActor = new ActorPrincipal(staffId, UUID.randomUUID(), Set.of(RoleCode.STAFF),
        Set.of("returns:process", "returns:read"), Map.of(facilityId, FacilityScopeLevel.OPERATE));
    ActorPrincipal managerActor = new ActorPrincipal(managerId, UUID.randomUUID(), Set.of(RoleCode.MANAGER),
        Set.of("rentals:update", "returns:read"), Map.of(facilityId, FacilityScopeLevel.MANAGE));

    User customer;
    User staff;
    Facility facility;
    StorageUnit unit;
    Reservation reservation;
    Rental rental;
    ReturnCase rc;

    @BeforeEach
    void setup() {
        service = new ReturnSettlementService(
            returnCaseRepository, rentalRepository, storageUnitRepository, userRepository,
            authorizationService, facilityScopeService, notificationService, auditLogService,
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "maintenanceTaskRepository", maintenanceTaskRepository);
        ReflectionTestUtils.setField(service, "userFacilityScopeRepository", userFacilityScopeRepository);
        ReflectionTestUtils.setField(service, "settlementEntityManager", entityManager);

        var calendar = mock(WorkflowBusinessCalendar.class);
        when(calendar.current()).thenReturn(WorkflowBusinessCalendar.parse(WorkflowBusinessCalendar.DEFAULT));
        ReflectionTestUtils.setField(service, "recoveryCalendar", calendar);

        customer = new User();
        ReflectionTestUtils.setField(customer, "id", customerId);
        customer.setFullName("Khách Sai Lệch");

        staff = new User();
        ReflectionTestUtils.setField(staff, "id", staffId);
        staff.setFullName("Nhân Viên Cơ Sở");
        staff.setStatus(UserStatus.ACTIVE);

        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setName("Kho Thủ Đức");

        UnitType unitType = new UnitType();
        unitType.setCode("S");
        unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", unitId);
        unit.setCode("TD-S01");
        unit.setUnitType(unitType);
        unit.setStatus(StorageUnitStatus.occupied);

        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", reservationId);
        reservation.setReservationCode("RSV-S01");
        reservation.setIdentityDocumentNumber("079200099999");

        rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", rentalId);
        rental.setCustomer(customer);
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setReservation(reservation);
        rental.setStatus(RentalStatus.return_requested);
        rental.setInventoryPending(true);
        rental.setMonthlyPrice(new BigDecimal("2000000"));
        InventoryTimelinePolicy.initializeRemovalWindow(rental, Instant.now().minus(1, ChronoUnit.DAYS));

        rc = new ReturnCase();
        ReflectionTestUtils.setField(rc, "id", returnId);
        rc.setRental(rental);
        rc.setFacility(facility);
        rc.setStorageUnit(unit);
        rc.setCustomer(customer);
        rc.setRequestedBy(customer);
        rc.setRequestedAt(Instant.now().minus(12, ChronoUnit.HOURS));
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setSetupExitRequested(true);
        rc.setStatus(ReturnCaseStatus.requested);
        rc.setRecoveryEligibleAt(rental.getInventoryRemovalDeadlineAt());
        rc.setReturnWindowDays(rental.getInventoryReturnWindowDays());
        rc.setReturnExtensionDays(rental.getInventoryReturnExtensionDays());
        rc.setReturnExtensionCount(0);
        rc.setReturnPolicyVersion(rental.getInventoryReturnPolicyVersion());

        when(returnCaseRepository.findOwnedForUpdate(returnId, customerId)).thenReturn(Optional.of(rc));
        when(returnCaseRepository.findByIdAndCustomer_Id(returnId, customerId)).thenReturn(Optional.of(rc));
        when(returnCaseRepository.findById(returnId)).thenReturn(Optional.of(rc));
        when(returnCaseRepository.findByIdForUpdate(returnId)).thenReturn(Optional.of(rc));
        when(rentalRepository.findByIdForUpdate(rentalId)).thenReturn(Optional.of(rental));
        when(returnCaseRepository.saveAndFlush(any(ReturnCase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rentalRepository.saveAndFlush(any(Rental.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findEligibleStaff(eq(UserStatus.ACTIVE), eq(RoleCode.STAFF), eq(facilityId), any()))
            .thenReturn(List.of(staff));
    }

    @Test
    void firstScheduleAndReschedulePreserveRemovalDeadlineAndRejectPastOrOverdueTimes() {
        Instant deadline = rental.getInventoryRemovalDeadlineAt();
        Instant firstAppt = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Instant secondAppt = deadline.minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);

        var scheduled = service.scheduleMismatchReturnAppointment(customerActor, returnId, firstAppt, 0L, "k1");
        assertThat(scheduled.status()).isEqualTo(ReturnCaseStatus.scheduled);
        assertThat(scheduled.scheduledAppointmentAt()).isEqualTo(firstAppt);
        assertThat(scheduled.scheduledDate()).isEqualTo(firstAppt.atZone(BusinessTime.ZONE).toLocalDate());
        assertThat(scheduled.inventoryRemovalDeadlineAt()).isEqualTo(deadline);

        var rescheduled = service.scheduleMismatchReturnAppointment(customerActor, returnId, secondAppt, scheduled.returnVersion(), "k2");
        assertThat(rescheduled.scheduledAppointmentAt()).isEqualTo(secondAppt);
        assertThat(rescheduled.inventoryRemovalDeadlineAt()).isEqualTo(deadline);
        assertThat(rescheduled.recoveryEligibleAt()).isEqualTo(deadline);
        assertThat(rescheduled.returnExtensionCount()).isZero();

        assertThatThrownBy(() -> service.scheduleMismatchReturnAppointment(
            customerActor, returnId, Instant.now().minus(5, ChronoUnit.MINUTES), rescheduled.returnVersion(), "past"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("quá khứ");

        assertThatThrownBy(() -> service.scheduleMismatchReturnAppointment(
            customerActor, returnId, deadline, rescheduled.returnVersion(), "at-deadline"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("trước hạn cuối");
    }

    @Test
    void legacyCaseWithoutAppointmentIsReadableAndCanSupplementAppointment() {
        rc.setScheduledDate(LocalDate.now(BusinessTime.ZONE).plusDays(1));
        rc.setScheduledAppointmentAt(null);

        var read = service.getReturnForCustomer(customerActor, returnId);
        assertThat(read.scheduledDate()).isNotNull();
        assertThat(read.scheduledAppointmentAt()).isNull();

        Instant appt = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        var updated = service.scheduleMismatchReturnAppointment(customerActor, returnId, appt, read.returnVersion(), "legacy-fill");
        assertThat(updated.scheduledAppointmentAt()).isEqualTo(appt);
    }

    @Test
    void extendsAtMostTwiceWithIdempotencyAndBlocksAfterArrival() {
        Instant initialDeadline = rental.getInventoryRemovalDeadlineAt();

        var first = service.extendMismatchReturnWindow(customerActor, returnId, 0L, "ext-1");
        assertThat(first.returnExtensionCount()).isEqualTo(1);
        assertThat(first.inventoryRemovalDeadlineAt()).isEqualTo(
            initialDeadline.atZone(BusinessTime.ZONE).plusDays(2).toInstant());
        assertThat(rc.getRecoveryEligibleAt()).isEqualTo(first.inventoryRemovalDeadlineAt());

        // Same idempotency key does not consume 2nd extension
        var retry = service.extendMismatchReturnWindow(customerActor, returnId, 0L, "ext-1");
        assertThat(retry.returnExtensionCount()).isEqualTo(1);

        var second = service.extendMismatchReturnWindow(customerActor, returnId, first.returnVersion(), "ext-2");
        assertThat(second.returnExtensionCount()).isEqualTo(2);
        assertThat(second.canExtendReturnWindow()).isFalse();

        assertThatThrownBy(() -> service.extendMismatchReturnWindow(customerActor, returnId, second.returnVersion(), "ext-3"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("hết số lần gia hạn");
    }

    @Test
    void customerClearanceDoesNotAutoCompleteOrReleaseUnitAndLocksNotAfterArrival() {
        var cleared = service.setCustomerClearance(customerActor, returnId, true, 0L);
        assertThat(cleared.customerClearedAt()).isNotNull();
        assertThat(cleared.status()).isEqualTo(ReturnCaseStatus.requested);
        assertThat(cleared.completedAt()).isNull();
        assertThat(rental.getActualReturnedAt()).isNull();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);

        // Staff records live arrival: customer scheduling & extension are now locked
        var arrived = service.recordReturnArrival(staffActor, returnId, null, null, null, cleared.returnVersion());
        assertThat(arrived.attendance().arrivedAt()).isNotNull();
        assertThat(arrived.attendance().recordedAt()).isEqualTo(arrived.attendance().arrivedAt());

        assertThatThrownBy(() -> service.scheduleMismatchReturnAppointment(
            customerActor, returnId, Instant.now().plus(2, ChronoUnit.HOURS), arrived.returnVersion(), "locked"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("tiếp nhận");
    }

    @Test
    void arrivalOneMinuteBeforeDeadlineIsProtectedThroughVerificationAndScheduler() {
        Instant deadline = Instant.now().plusSeconds(60);
        rental.setInventoryRemovalDeadlineAt(deadline);
        rc.setRecoveryEligibleAt(deadline);

        var arrived = service.recordReturnArrival(staffActor, returnId, null, null, null, 0L);
        Instant arrivedAt = arrived.attendance().arrivedAt();
        assertThat(arrivedAt).isBefore(deadline);

        // Simulate deadline passing while verification/inspection continues
        Instant pastDeadline = deadline.plusSeconds(120);
        rental.setInventoryRemovalDeadlineAt(pastDeadline.minusSeconds(120));
        rc.setRecoveryEligibleAt(pastDeadline.minusSeconds(120));

        // Scheduler scan does not hijack timely arrival into recovery
        when(returnCaseRepository.findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(any(), any()))
            .thenReturn(List.of(rc));
        assertThat(service.escalateNoShowReturns()).isZero();
        verify(maintenanceTaskRepository, never()).save(any());

        // Verification requires arrival first, and succeeds past deadline without changing arrivedAt
        var verified = service.verifyReturnIdentity(staffActor, returnId, UUID.randomUUID(), "079200099999");
        assertThat(verified.attendance().verifiedAt()).isNotNull();
        assertThat(verified.attendance().arrivedAt()).isEqualTo(arrivedAt);
    }

    @Test
    void lateRecordingWithOnTimeEvidenceEntersManagerReviewWithoutSelfBackdatingAndProtectsFromScheduler() {
        Instant deadline = Instant.now().minus(10, ChronoUnit.MINUTES);
        rental.setInventoryRemovalDeadlineAt(deadline);
        rc.setRecoveryEligibleAt(deadline);
        Instant actualOnTime = deadline.minus(2, ChronoUnit.MINUTES);

        assertThatThrownBy(() -> service.recordReturnArrival(staffActor, returnId, actualOnTime, null, null, 0L))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("bằng chứng");

        var pending = service.recordReturnArrival(
            staffActor, returnId, actualOnTime, "Sổ quầy lễ tân ghi nhận khách đến lúc 23:58", null, 0L);
        assertThat(pending.attendance().arrivedAt()).isNull();
        assertThat(pending.attendance().lateArrivalClaimedAt()).isEqualTo(actualOnTime);
        assertThat(pending.attendance().recordedAt()).isAfter(deadline);
        assertThat(pending.attendance().reviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_PENDING);
        assertThat(pending.attendance().reviewStartsAt()).isNotNull();
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(rc)).isTrue();

        when(returnCaseRepository.findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(any(), any()))
            .thenReturn(List.of(rc));
        assertThat(service.escalateNoShowReturns()).isZero();

        var approved = service.reviewReturnArrival(
            managerActor, returnId, true, "Đã đối chiếu sổ quầy lễ tân", staffId, null, "APPROVED_ON_TIME");
        assertThat(approved.attendance().arrivedAt()).isEqualTo(actualOnTime);
        assertThat(approved.attendance().recordedAt()).isAfter(deadline);
        assertThat(approved.attendance().reviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_APPROVED_ON_TIME);
        assertThat(approved.attendance().reverificationStaffId()).isEqualTo(staffId);
        assertThat(approved.attendance().expiresAt()).isAfter(Instant.now());

        // Assigned staff can verify within the reopened 30-min window
        var verified = service.verifyReturnIdentity(
            staffActor, returnId, UUID.randomUUID(), "079200099999", approved.attendance().reverificationStartedAt());
        assertThat(verified.attendance().verifiedAt()).isNotNull();
    }

    @Test
    void arrivalAfterDeadlineCannotBeApprovedOnTimeAndRequiresManagerExceptionOrAbsence() {
        Instant deadline = Instant.now().minus(30, ChronoUnit.MINUTES);
        rental.setInventoryRemovalDeadlineAt(deadline);
        rc.setRecoveryEligibleAt(deadline);
        Instant afterDeadline = deadline.plus(5, ChronoUnit.MINUTES);

        var pending = service.recordReturnArrival(
            staffActor, returnId, afterDeadline, "Khách đến trễ 5 phút do kẹt xe", null, 0L);
        assertThat(pending.attendance().reviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_PENDING);
        assertThat(pending.attendance().arrivedAt()).isNull();

        assertThatThrownBy(() -> service.reviewReturnArrival(
            managerActor, returnId, true, "Thử duyệt đúng hạn", staffId, null, "APPROVED_ON_TIME"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ngoại lệ");

        var exceptionApproved = service.reviewReturnArrival(
            managerActor, returnId, true, "Chấp nhận ngoại lệ hỗ trợ khách dọn kho", staffId, null, "FACILITY_EXCEPTION");
        assertThat(exceptionApproved.attendance().arrivedAt()).isEqualTo(afterDeadline);
        assertThat(exceptionApproved.attendance().reviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_FACILITY_EXCEPTION);
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(rc)).isTrue();
    }

    @Test
    void verificationRequiresArrivalFirstAndTwoFailuresTriggerReviewWithSingleReopen() {
        assertThatThrownBy(() -> service.verifyReturnIdentity(staffActor, returnId, UUID.randomUUID(), "079200099999"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ghi nhận khách đến");

        service.recordReturnArrival(staffActor, returnId, null, null, null, 0L);
        service.verifyReturnIdentity(staffActor, returnId, UUID.randomUUID(), "079200000001");
        var secondFail = service.verifyReturnIdentity(staffActor, returnId, UUID.randomUUID(), "079200000002");
        assertThat(secondFail.attendance().attempts()).isEqualTo(2);
        assertThat(secondFail.attendance().reviewStartsAt()).isNotNull();

        var reopened = service.reopenReturnVerification(managerActor, returnId, staffId, "Khách bổ sung CCCD bản gốc");
        assertThat(reopened.attendance().reverificationStartedAt()).isNotNull();
        assertThat(reopened.attendance().attempts()).isZero();
    }
}
