package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ErrorCode;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Comprehensive verification of the unified Setup & Inspection specification (Sections 1-8):
 * A (Initialization/Time/Free-Setup), B (Setup & Appointments), C (Unified Extensions),
 * D (Missed & Last-Minute Reconciliation), E (2-Attempt Verification), F (72h Completion),
 * G (Scheduler & Notifications), H (Concurrency & Authorization).
 */
class UnifiedIntakeSpecTests {

    static class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant initial) { this.instant = initial; }
        void set(Instant next) { this.instant = next; }
        void advanceSeconds(long seconds) { this.instant = this.instant.plusSeconds(seconds); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    RentalRepository rentals = mock(RentalRepository.class);
    CheckInRepository checkIns = mock(CheckInRepository.class);
    FileAssetRepository files = mock(FileAssetRepository.class);
    FacilityScopeService scope = mock(FacilityScopeService.class);
    NotificationService notifications = mock(NotificationService.class);
    ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    IntakeVerificationAttemptRepository verifAttempts = mock(IntakeVerificationAttemptRepository.class);
    IntakeIdempotencyRecordRepository idemRecords = mock(IntakeIdempotencyRecordRepository.class);
    MaintenanceTaskRepository tasks = mock(MaintenanceTaskRepository.class);
    UserRepository users = mock(UserRepository.class);

    // 2026-11-25 10:30:00 ICT == 2026-11-25 03:30:00Z
    Instant t0 = Instant.parse("2026-11-25T03:30:00Z");
    MutableClock clock = new MutableClock(t0);
    IntakePolicyProperties policy = new IntakePolicyProperties();
    IntakeTimelineCalculator calculator = new IntakeTimelineCalculator(policy);
    IntakeWorkflowService workflow;
    IntakeDeadlineScheduler scheduler;

    UUID facilityId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID staffId = UUID.randomUUID();
    UUID managerId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID checkInId = UUID.randomUUID();
    Rental rental;
    CheckIn checkIn;
    ActorPrincipal customer = actor(customerId, RoleCode.CUSTOMER);
    ActorPrincipal staff = actor(staffId, RoleCode.STAFF);
    ActorPrincipal manager = actor(managerId, RoleCode.MANAGER);

    static ActorPrincipal actor(UUID id, RoleCode role) {
        return new ActorPrincipal(id, UUID.randomUUID(), Set.of(role), Set.of(), Map.of());
    }

    @BeforeEach
    void setup() {
        workflow = new IntakeWorkflowService(
            rentals, checkIns, files, scope, notifications, new ObjectMapper(),
            returns, clock, policy, verifAttempts, idemRecords, tasks
        );
        scheduler = new IntakeDeadlineScheduler(
            rentals, checkIns, tasks, returns, notifications, users, clock, policy
        );

        User customerUser = mock(User.class);
        when(customerUser.getId()).thenReturn(customerId);
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        UnitType unitType = new UnitType();
        unitType.setCode("M");
        unitType.setSetupDeadlineDays(7);
        unitType.setSetupMaxExtensionDays(3);
        StorageUnit unit = new StorageUnit();
        unit.setCode("M001");
        unit.setUnitType(unitType);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(reservationId);
        when(reservation.getReservationCode()).thenReturn("RSV-M001");
        when(reservation.getIdentityDocumentNumber()).thenReturn("079200012345");

        rental = spy(new Rental());
        when(rental.getId()).thenReturn(UUID.randomUUID());
        rental.setCustomer(customerUser);
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setReservation(reservation);
        rental.setStatus(RentalStatus.setup);
        rental.setSetupStartedAt(t0);
        rental.setSetupEndsAt(t0.plus(72, ChronoUnit.HOURS));
        rental.setInventoryPending(true);
        calculator.initializeOnReceipt(rental, t0);

        checkIn = spy(new CheckIn());
        when(checkIn.getId()).thenReturn(checkInId);
        checkIn.setReservation(reservation);
        checkIn.setStatus(CheckInStatus.setup_in_progress);

        when(rentals.findByReservationIdForUpdate(reservationId)).thenReturn(Optional.of(rental));
        when(rentals.findByReservation_Id(reservationId)).thenReturn(Optional.of(rental));
        when(rentals.findAll()).thenReturn(List.of(rental));
        when(checkIns.findByReservation_Id(reservationId)).thenReturn(Optional.of(checkIn));
        when(rentals.countActiveAppointmentsInSlot(any(), any(), any(), any())).thenReturn(0L);
        when(tasks.findByRental_IdAndTaskTypeAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(returns.findByStatusIn(any())).thenReturn(List.of());
        when(users.findDistinctByRoles_CodeAndStatus(any(), any())).thenReturn(List.of());
    }

    private UUID validPhotoAt(Instant createdAt) {
        UUID photoId = UUID.randomUUID();
        UUID cid = checkIn.getId();
        FileAsset asset = mock(FileAsset.class);
        User uploader = mock(User.class);
        when(uploader.getId()).thenReturn(staffId);
        when(asset.getId()).thenReturn(photoId);
        when(asset.getStatus()).thenReturn(FileAssetStatus.ACTIVE);
        when(asset.getContentType()).thenReturn("image/jpeg");
        when(asset.getEntityType()).thenReturn("CHECK_IN");
        when(asset.getEntityId()).thenReturn(cid);
        when(asset.getCreatedAt()).thenReturn(createdAt);
        when(asset.getUploadedBy()).thenReturn(uploader);
        when(files.findById(photoId)).thenReturn(Optional.of(asset));
        return photoId;
    }

    // --- Group A: Initialization, sizes, free setup independence, snapshot ---

    @Test
    void a01_to_a04_and_a13_initializesExactSizeDurationsAndRejectsUnknownSize() {
        for (var entry : Map.of("S", 120L, "M", 168L, "L", 216L, "XL", 288L).entrySet()) {
            Rental r = new Rental();
            UnitType ut = new UnitType();
            ut.setCode(entry.getKey());
            ut.setSetupDeadlineDays((int)(entry.getValue() / 24));
            StorageUnit su = new StorageUnit();
            su.setUnitType(ut);
            r.setStorageUnit(su);
            calculator.initializeOnReceipt(r, t0);
            assertThat(r.getOriginalInspectionArrivalDeadlineAt()).isEqualTo(t0.plus(entry.getValue(), ChronoUnit.HOURS));
            assertThat(r.getInventoryFinalDeadlineAt()).isEqualTo(r.getOriginalInspectionArrivalDeadlineAt());
            assertThat(r.getSetupExtensionCount()).isZero();
        }

        Rental unknown = new Rental();
        UnitType custom = new UnitType();
        custom.setCode("XXL");
        StorageUnit su = new StorageUnit();
        su.setUnitType(custom);
        unknown.setStorageUnit(su);
        calculator.initializeOnReceipt(unknown, t0);
        assertThat(unknown.getOriginalInspectionArrivalDeadlineAt()).isEqualTo(t0.plus(120, ChronoUnit.HOURS));
    }

    @Test
    void a06_a07_a09_a12_repeatedReceiptDoesNotResetAndFreeSetupDoesNotShiftDeadline() {
        Instant d0 = rental.getOriginalInspectionArrivalDeadlineAt();
        rental.setSetupEndsAt(t0.plus(24, ChronoUnit.HOURS)); // change free setup
        calculator.initializeOnReceipt(rental, t0.plusSeconds(500));
        assertThat(rental.getOriginalInspectionArrivalDeadlineAt()).isEqualTo(d0);
        assertThat(calculator.arrivalDeadline(rental)).isEqualTo(d0);

        // A07: 23:59:59 across year boundary preserves exact second
        Instant yearEnd = Instant.parse("2026-12-31T16:59:59Z"); // 23:59:59 ICT
        Rental r = new Rental();
        r.setStorageUnit(rental.getStorageUnit());
        calculator.initializeOnReceipt(r, yearEnd);
        assertThat(r.getOriginalInspectionArrivalDeadlineAt()).isEqualTo(Instant.parse("2027-01-07T16:59:59Z"));
    }

    // --- Group B: Setup completion & Appointment rules ---

    @Test
    void b01_to_b05_setupCutoffBoundaryAndIdempotentDeclaration() {
        Instant d = calculator.arrivalDeadline(rental);
        Instant c = d.minus(6, ChronoUnit.HOURS);
        assertThat(calculator.setupCutoff(rental)).isEqualTo(c);

        // B03: 1s after C is blocked
        clock.set(c.plusSeconds(1));
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.SETUP_CUTOFF_EXCEEDED);

        // B02: exact C succeeds; B05 repeated call preserves original timestamp
        clock.set(c);
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));
        assertThat(rental.getMoveInCompletedAt()).isEqualTo(c);

        clock.set(c.minusSeconds(100));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));
        assertThat(rental.getMoveInCompletedAt()).isEqualTo(c);
        assertThat(calculator.arrivalDeadline(rental)).isEqualTo(d);
    }

    @Test
    void setupCompleteAndArrivalAllowedBeforeScheduledHandoverDateWhenReceiptAlreadyConfirmed() {
        Instant scheduledHandover = t0.plus(48, ChronoUnit.HOURS);
        rental.setSetupStartedAt(scheduledHandover);

        Instant earlyNow = t0.plus(5, ChronoUnit.MINUTES);
        clock.set(earlyNow);

        assertThat(calculator.canDeclareSetupComplete(rental, earlyNow)).isTrue();
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));
        assertThat(rental.getMoveInCompletedAt()).isEqualTo(earlyNow);
        assertThat(calculator.isArrivalOnTime(rental, earlyNow.plus(10, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    void b06_to_b17_appointmentLeadTimeBoundsAndSlotCapacity() {
        Instant d = calculator.arrivalDeadline(rental);
        // B11: < 6h from now when setup not complete -> rejected
        clock.set(t0.plus(10, ChronoUnit.HOURS));
        Instant tooSoon = clock.instant().plus(6, ChronoUnit.HOURS).minusSeconds(1);
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("APPOINTMENT", tooSoon, null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.SETUP_CUTOFF_EXCEEDED);

        // B16: > D -> rejected
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("APPOINTMENT", d.plusSeconds(1), null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.DEADLINE_EXPIRED);

        // B17: full slot -> NO_SLOT
        Instant validA = Instant.parse("2026-12-01T08:00:00Z"); // 15:00 ICT
        when(rentals.countActiveAppointmentsInSlot(eq(facilityId), any(), any(), any()))
            .thenReturn((long) policy.getAppointmentSlotCapacity());
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("APPOINTMENT", validA, null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.NO_SLOT);

        // B06 & B09: schedule before setup, then pass C without setup -> NOT_READY, C resets to D-6h
        when(rentals.countActiveAppointmentsInSlot(eq(facilityId), any(), any(), any())).thenReturn(0L);
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("APPOINTMENT", validA, null, null, null));
        assertThat(rental.getAppointmentStatus()).isEqualTo(IntakeTimelineCalculator.APPT_ACTIVE);
        assertThat(calculator.setupCutoff(rental)).isEqualTo(validA.minus(6, ChronoUnit.HOURS));

        clock.set(validA.minus(6, ChronoUnit.HOURS).plusSeconds(1));
        scheduler.process();
        assertThat(rental.getAppointmentStatus()).isEqualTo(IntakeTimelineCalculator.APPT_NOT_READY);
        assertThat(rental.getInventoryEscalatedAt()).isNull(); // no early recovery!
        assertThat(calculator.setupCutoff(rental)).isEqualTo(d.minus(6, ChronoUnit.HOURS));
    }

    // --- Group C: Unified Extensions ---

    @Test
    void c03_to_c21_unifiedExtensionsRespectLimitsWholeDaysBoundariesAndPostV() {
        Instant d0 = calculator.arrivalDeadline(rental); // 2026-12-02 03:30Z

        // C21: non-whole-day extension rejected under P06
        clock.set(d0.minusSeconds(1)); // C10 & C13: inside last 6h, 1s before D
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", d0.plus(12, ChronoUnit.HOURS), null, null, null)))
            .isInstanceOf(ApiException.class);

        // C03: 1st extension +72h (3 days)
        Instant d1 = d0.plus(72, ChronoUnit.HOURS);
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", d1, null, null, null));
        assertThat(calculator.arrivalDeadline(rental)).isEqualTo(d1);
        assertThat(rental.getSetupExtensionCount()).isEqualTo(1);

        // C04: 2nd extension +72h -> max 312h
        Instant d2 = d1.plus(72, ChronoUnit.HOURS);
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", d2, null, null, null));
        assertThat(calculator.arrivalDeadline(rental)).isEqualTo(d2);
        assertThat(rental.getSetupExtensionCount()).isEqualTo(2);

        // C05: 3rd extension blocked with EXTENSION_LIMIT
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", d2.plus(24, ChronoUnit.HOURS), null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.EXTENSION_LIMIT);

        // C11: exact D rejected
        rental.setSetupExtensionCount(0);
        clock.set(d2);
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", d2.plus(24, ChronoUnit.HOURS), null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.DEADLINE_EXPIRED);
    }

    // --- Group D & E & F: Reconciliation, 2-Attempt Verification, 72h Completion ---

    @Test
    void d05_d08_d09_d10_lastMinuteReconciliationAndFixedR() {
        Instant d = calculator.arrivalDeadline(rental);
        clock.set(d.plusSeconds(1));
        scheduler.process();
        assertThat(rental.getAttendanceReviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_PENDING);
        Instant expectedR = d.plus(30, ChronoUnit.MINUTES);
        assertThat(rental.getAttendanceReviewDeadlineAt()).isEqualTo(expectedR);
        verify(tasks, never()).saveAndFlush(any());

        // D10: with evidence at R+1s, escalates to Manager/BO without auto-recovery
        rental.setAttendanceEvidenceNote("Sổ quầy: khách đến lúc 10:29:50");
        clock.set(expectedR.plusSeconds(1));
        scheduler.process();
        verify(tasks, never()).saveAndFlush(any());
        assertThat(rental.getInventoryEscalatedAt()).isNull();

        // D09: without evidence at R+1s, creates exactly 1 recovery task
        rental.setAttendanceEvidenceNote(null);
        scheduler.process();
        verify(tasks, times(1)).saveAndFlush(any());
        assertThat(rental.getAttendanceReviewStatus()).isEqualTo(IntakeTimelineCalculator.REVIEW_CONFIRMED_ABSENT);
    }

    @Test
    void customerWithoutSetupCannotBeAdmittedOrApprovedOnTimeEvenIfPresent() {
        Instant d = calculator.arrivalDeadline(rental);
        clock.set(d.minus(2, ChronoUnit.HOURS)); // inside last 6h, setup not completed
        assertThatThrownBy(() -> workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null)))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.SETUP_CUTOFF_EXCEEDED);

        clock.set(d.plusSeconds(1));
        scheduler.process();
        assertThatThrownBy(() -> workflow.change(manager, reservationId, new IntakeWorkflowService.Command(
            "RESOLVE_REVIEW", null, "Khách có mặt nhưng chưa setup", null, null, null, null, null, null, d.minusSeconds(10), null, "APPROVED_ON_TIME", null
        ))).isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.SETUP_CUTOFF_EXCEEDED);
    }

    @Test
    void d15_d16_missedAppointmentAnd2HourReminders() {
        Instant appt = t0.plus(48, ChronoUnit.HOURS);
        clock.set(t0.plus(1, ChronoUnit.HOURS));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("APPOINTMENT", appt, null, null, null));

        // Before A+30m -> cannot mark MISSED
        clock.set(appt.plus(29, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("MARK_MISSED", null, null, null, null)))
            .isInstanceOf(ApiException.class);

        // At A+30m -> Staff marks MISSED
        clock.set(appt.plus(30, ChronoUnit.MINUTES));
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("MARK_MISSED", null, null, null, null));
        assertThat(rental.getAppointmentStatus()).isEqualTo(IntakeTimelineCalculator.APPT_MISSED);

        // G06: 2h later scheduler sends reminder
        clock.advanceSeconds(2 * 3600L);
        scheduler.process();
        verify(notifications, atLeastOnce()).createNotification(
            eq(customerId), eq(NotificationType.CHECKIN), eq("Nhắc đặt lại lịch kiểm kê"), any(), eq(reservationId));
    }

    @Test
    void confirmingArrivalStartsThe72hClockBeforeAnyIdentityVerificationHappens() {
        clock.set(t0.plus(12, ChronoUnit.HOURS));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));

        assertThat(rental.getInspectionClockStartedAt()).isNull();
        assertThat(rental.getInspectionCompletionDeadlineAt()).isNull();

        Instant arriveTime = t0.plus(24, ChronoUnit.HOURS);
        clock.set(arriveTime);
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null));

        // The clock runs from the arrival confirmation, with no verification involved yet.
        assertThat(rental.getInspectionClockStartedAt()).isEqualTo(arriveTime);
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(arriveTime.plus(72, ChronoUnit.HOURS));
        assertThat(rental.getFirstIdentityVerificationAt()).isNull();
        assertThat(rental.getVerificationAttemptCount()).isZero();

        // A repeated arrival confirmation must never restart or extend the window.
        clock.set(t0.plus(30, ChronoUnit.HOURS));
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null));
        assertThat(rental.getInspectionClockStartedAt()).isEqualTo(arriveTime);
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(arriveTime.plus(72, ChronoUnit.HOURS));
    }

    @Test
    void expiredArrivalAnchoredWindowBlocksVerificationAndInspectionCompletion() {
        clock.set(t0.plus(12, ChronoUnit.HOURS));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));
        Instant arriveTime = t0.plus(24, ChronoUnit.HOURS);
        clock.set(arriveTime);
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null));
        Instant f = arriveTime.plus(72, ChronoUnit.HOURS);

        // Past F the verification window is closed even though nothing was verified yet.
        clock.set(f.plus(1, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, null, "079200012345", validPhotoAt(arriveTime), null, "idem-late", null, null, null, null, "PASS", null
        ))).isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.DEADLINE_EXPIRED);
    }

    @Test
    void e01_to_e18_confirmingArrivalStarts72hClockAndProtectsTechError() {
        clock.set(t0.plus(12, ChronoUnit.HOURS));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));

        // Arrive one day before D so the 72h completion window still spans D.
        Instant arriveTime = calculator.arrivalDeadline(rental).minus(24, ChronoUnit.HOURS);
        clock.set(arriveTime);
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null));
        assertThat(rental.getInspectionClockStartedAt()).isEqualTo(arriveTime);
        Instant arrivalAnchorF = arriveTime.plus(72, ChronoUnit.HOURS);
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(arrivalAnchorF);
        assertThat(rental.getFirstIdentityVerificationAt()).isNull();

        // E13: TECH_ERROR does NOT initialize V or consume attempt, and never moves the clock.
        workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, "Camera mất kết nối", null, null, null, "idem-tech-1", null, null, null, null, "TECH_ERROR", null
        ));
        assertThat(rental.getFirstIdentityVerificationAt()).isNull();
        assertThat(rental.getVerificationAttemptCount()).isZero();
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(arrivalAnchorF);

        // E02: 1st substantive FAIL records V and leaves 1 attempt, but keeps the arrival anchor.
        Instant vTime = arriveTime.plus(5, ChronoUnit.MINUTES);
        clock.set(vTime);
        workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, "CCCD mờ, chờ mang bản gốc", "079200012345", null, null, "idem-fail-1", null, null, null, null, "FAIL", null
        ));
        assertThat(rental.getFirstIdentityVerificationAt()).isEqualTo(vTime);
        assertThat(rental.getInspectionClockStartedAt()).isEqualTo(arriveTime);
        Instant expectedF = arrivalAnchorF;
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(expectedF);
        assertThat(rental.getVerificationAttemptCount()).isEqualTo(1);
        assertThat(rental.getVerificationStatus()).isEqualTo(IntakeTimelineCalculator.VERIF_IN_PROGRESS);

        // C18: Normal extension blocked after V
        assertThatThrownBy(() -> workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("EXTEND_SETUP", calculator.arrivalDeadline(rental).plus(24, ChronoUnit.HOURS), null, null, null)))
            .isInstanceOf(ApiException.class);

        // F06: Even if D passes during 72h window, scheduler must NOT recover by D
        clock.set(calculator.arrivalDeadline(rental).plus(1, ChronoUnit.HOURS));
        scheduler.process();
        verify(tasks, never()).saveAndFlush(any());

        // E03: 2nd attempt PASS within F preserves F and unlocks direct inspection
        clock.set(expectedF.minus(10, ChronoUnit.MINUTES));
        UUID photoId = validPhotoAt(arriveTime.plus(10, ChronoUnit.MINUTES));
        workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, null, "079200012345", photoId, null, "idem-pass-2", null, null, null, null, "PASS", null
        ));
        assertThat(rental.getVerificationAttemptCount()).isEqualTo(2);
        assertThat(rental.getVerificationStatus()).isEqualTo(IntakeTimelineCalculator.VERIF_PASSED);
        assertThat(rental.getInspectionCompletionDeadlineAt()).isEqualTo(expectedF);

        // E18: 3rd / repeat verification after PASS blocked
        assertThatThrownBy(() -> workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, null, "079200012345", photoId, null, "idem-3", null, null, null, null, "PASS", null
        ))).isInstanceOf(ApiException.class);

        // F04: Completing inspection at exact F succeeds
        clock.set(expectedF);
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_INSPECTION", null, null, null, null));
        assertThat(rental.getInspectionCompletedAt()).isEqualTo(expectedF);
    }

    @Test
    void e04_e05_secondSubstantiveFailTriggersImmediateFailureUnderP02AndBlocksThirdAttempt() {
        clock.set(t0.plus(12, ChronoUnit.HOURS));
        workflow.change(customer, reservationId,
            new IntakeWorkflowService.Command("COMPLETE_SETUP", null, null, null, null));
        clock.set(t0.plus(20, ChronoUnit.HOURS));
        workflow.change(staff, reservationId,
            new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null));

        workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, "Sai CCCD lần 1", "000", null, null, null, null, null, null, null, "FAIL", null
        ));
        workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, "Người khác đến thay lần 2", "000", null, null, null, null, null, null, null, "FAIL", null
        ));

        assertThat(rental.getVerificationStatus()).isEqualTo(IntakeTimelineCalculator.VERIF_FAILED_MAX_ATTEMPTS);
        assertThat(rental.getInspectionFailureReason()).contains("Người khác đến thay lần 2");
        verify(tasks, times(1)).saveAndFlush(any());

        assertThatThrownBy(() -> workflow.change(staff, reservationId, new IntakeWorkflowService.Command(
            "VERIFY", null, "Lần 3", "079200012345", null, null, null, null, null, null, null, "FAIL", null
        ))).isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode())
            .isEqualTo(ErrorCode.VERIFICATION_LIMIT);
    }

    // --- Group H: Concurrency / Optimistic Locking ---

    @Test
    void h03_h05_concurrentExtensionsWithOptimisticVersionAllowOnlyOneMutation() throws Exception {
        long initialVersion = rental.getIntakeVersion();
        Instant d0 = calculator.arrivalDeadline(rental);
        Instant nextD = d0.plus(24, ChronoUnit.HOURS);

        // Synchronize two threads trying to mutate using the same expectedVersion.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        // Guard synchronized mock access to simulate DB row lock for concurrent Java test
        when(rentals.findByReservationIdForUpdate(reservationId)).thenAnswer(inv -> {
            synchronized (rental) { return Optional.of(rental); }
        });

        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                final String key = "k-" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        synchronized (rental) {
                            workflow.change(customer, reservationId, new IntakeWorkflowService.Command(
                                "EXTEND_SETUP", nextD, null, null, null, null, key, null, null, null, null, null, initialVersion
                            ));
                        }
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        failed.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.set(t);
                    }
                });
            }
            ready.await();
            go.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(unexpected.get()).isNull();
        assertThat(success.get()).isEqualTo(1);
        assertThat(failed.get()).isEqualTo(1);
        assertThat(rental.getSetupExtensionCount()).isEqualTo(1);
    }
}
