package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.MaintenanceRecoveryType;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.RentalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RentalSetupLifecycleServiceTests {

    @Mock RentalRepository rentalRepository;
    @Mock NotificationService notificationService;
    @Mock MaintenanceTaskRepository maintenanceTaskRepository;

    RentalSetupLifecycleService service;
    Rental rental;

    @BeforeEach
    void setUp() {
        service = new RentalSetupLifecycleService(rentalRepository, notificationService);
        ReflectionTestUtils.setField(service, "maintenanceTaskRepository", maintenanceTaskRepository);

        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setName("Cơ sở kiểm thử");
        StorageUnit unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", UUID.randomUUID());
        unit.setCode("A-01");
        unit.setFacility(facility);
        User customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        Reservation reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setReservationCode("RSV-TEST-01");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setReservation(reservation);
        rental.setCustomer(customer);
        rental.setStatus(RentalStatus.active);
        rental.setStartDate(LocalDate.now().minusDays(3));
        rental.setSetupEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        rental.setInventoryPending(true);
        rental.setInventoryDueAt(Instant.now().minus(1, ChronoUnit.DAYS));
        rental.setInventoryFinalDeadlineAt(Instant.now().minusSeconds(60));
    }

    @Test
    void escalatesExpiredInventoryRecoveryOnce() {
        // The merged lifecycle service keeps its own inventory escalation branch: once the
        // rental's final inventory deadline has passed it creates the recovery task and
        // stamps inventoryEscalatedAt so no other pass can escalate the case again.
        // NOTE: IntakeDeadlineScheduler implements the same escalation, so which component
        // wins is a race; the dedupe flag keeps it to a single task.
        when(rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)).thenReturn(List.of());
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));

        service.advanceSetupRentals();

        assertThat(rental.getInventoryEscalatedAt()).isNotNull();
        var task = org.mockito.ArgumentCaptor.forClass(MaintenanceTask.class);
        verify(maintenanceTaskRepository).saveAndFlush(task.capture());
        assertThat(task.getValue().getRecoveryType()).isEqualTo(MaintenanceRecoveryType.INVENTORY_OVERDUE);
        assertThat(task.getValue().getPriority()).isEqualTo(com.storagehub.domain.model.TaskPriority.high);
        assertThat(task.getValue().getDeadlineAt()).isAfter(Instant.now());

        service.advanceSetupRentals();

        verify(maintenanceTaskRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void leavesAnAlreadyEscalatedCaseToTheDeadlineScheduler() {
        rental.setInventoryEscalatedAt(Instant.now().minusSeconds(30));
        when(rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)).thenReturn(List.of());
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));

        service.advanceSetupRentals();

        verify(maintenanceTaskRepository, never()).saveAndFlush(any());
    }

    @Test
    void startsPaidRentalOnAgreedDateEvenIfFreeSetupHasNotEnded() {
        var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        rental.setStatus(RentalStatus.setup);
        rental.setStartDate(LocalDate.now(zone));
        rental.setSetupEndsAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)).thenReturn(List.of(rental));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of());

        service.advanceSetupRentals();

        assertThat(rental.getStatus()).isEqualTo(RentalStatus.active);
    }

    @Test
    void sendsOnlyLastDayReminderWhenEarlierReminderWasMissed() {
        var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        rental.setStatus(RentalStatus.setup);
        rental.setSetupEndsAt(LocalDate.now(zone).plusDays(1).atTime(12, 0).atZone(zone).toInstant());
        when(rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)).thenReturn(List.of(rental));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of());
        service.advanceSetupRentals();
        service.advanceSetupRentals();
        verify(notificationService).createNotification(
            org.mockito.ArgumentMatchers.eq(rental.getCustomer().getId()),
            org.mockito.ArgumentMatchers.eq(com.storagehub.domain.model.NotificationType.CHECKIN),
            org.mockito.ArgumentMatchers.eq("Hôm nay là ngày chuẩn bị miễn phí cuối cùng"),
            org.mockito.ArgumentMatchers.contains("Kỳ thuê tính từ " + rental.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
            org.mockito.ArgumentMatchers.eq(rental.getReservation().getId()));
        verify(notificationService, never()).createNotification(any(), any(),
            org.mockito.ArgumentMatchers.eq("Nhắc thời gian chuẩn bị sắp kết thúc"), any(), any());
    }

    @Test
    void remindsOnceWhenTheFourDayMilestoneIsReachedBeforeFinalDeadline() {
        rental.setInventoryFinalDeadlineAt(Instant.now().plus(3, ChronoUnit.DAYS));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));

        service.advanceSetupRentals();
        service.advanceSetupRentals();

        assertThat(rental.getInventoryDeadlineReminderAt()).isNotNull();
        assertThat(rental.getInventoryEscalatedAt()).isNull();
        verify(notificationService).createNotification(
            org.mockito.ArgumentMatchers.eq(rental.getCustomer().getId()),
            org.mockito.ArgumentMatchers.eq(com.storagehub.domain.model.NotificationType.CHECKIN),
            org.mockito.ArgumentMatchers.eq("Sắp đến hạn kiểm kê hàng hóa"), any(String.class),
            org.mockito.ArgumentMatchers.eq(rental.getReservation().getId()));
        verify(maintenanceTaskRepository, never()).saveAndFlush(any());
    }

    @Test
    void waitsForRemovalDeadlineEvenIfTheOriginalInventoryDeadlinePassed() {
        rental.setInventoryRemovalDeadlineAt(Instant.now().plusSeconds(60));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));

        service.advanceSetupRentals();

        assertThat(rental.getInventoryEscalatedAt()).isNull();
        verify(maintenanceTaskRepository, never()).saveAndFlush(any());
    }

    @Test
    void enforcesSizeSpecificRecoveryMilestonesForSmallAndExtraLargeUnits() {
        var zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        Instant noticeDay = LocalDate.of(2026, 11, 20).atTime(14, 0).atZone(zone).toInstant();

        // Size S (3 days): effective 21/11..23/11, recovery starts at 24/11 00:00.
        com.storagehub.domain.model.UnitType smallType = new com.storagehub.domain.model.UnitType();
        smallType.setCode("S");
        rental.getStorageUnit().setUnitType(smallType);
        rental.setInventoryFinalDeadlineAt(noticeDay.plus(1, ChronoUnit.DAYS));
        rental.setInventoryRemovalDeadlineAt(InventoryTimelinePolicy.removalDeadline(noticeDay, 3));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));

        Instant day3End = LocalDate.of(2026, 11, 23).atTime(23, 59, 59).atZone(zone).toInstant();
        Instant day4Start = LocalDate.of(2026, 11, 24).atTime(0, 0).atZone(zone).toInstant();
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(day3End);
            service.advanceSetupRentals();
            assertThat(rental.getInventoryEscalatedAt()).isNull();
            verify(maintenanceTaskRepository, never()).saveAndFlush(any());

            clock.when(Instant::now).thenReturn(day4Start);
            service.advanceSetupRentals();
            service.advanceSetupRentals(); // idempotent on repeated cron runs
            assertThat(rental.getInventoryEscalatedAt()).isEqualTo(day4Start);
            verify(maintenanceTaskRepository, times(1)).saveAndFlush(any());
        }

        // Size XL (10 days): NOT escalated on day 8 (legacy 7-day mark), escalated on day 11.
        Rental xlRental = new Rental();
        ReflectionTestUtils.setField(xlRental, "id", UUID.randomUUID());
        xlRental.setFacility(rental.getFacility());
        StorageUnit xlUnit = new StorageUnit();
        ReflectionTestUtils.setField(xlUnit, "id", UUID.randomUUID());
        xlUnit.setCode("XL-01");
        xlUnit.setFacility(rental.getFacility());
        com.storagehub.domain.model.UnitType xlType = new com.storagehub.domain.model.UnitType();
        xlType.setCode("XL");
        xlUnit.setUnitType(xlType);
        xlRental.setStorageUnit(xlUnit);
        xlRental.setReservation(rental.getReservation());
        xlRental.setCustomer(rental.getCustomer());
        xlRental.setStatus(RentalStatus.active);
        xlRental.setInventoryPending(true);
        xlRental.setInventoryFinalDeadlineAt(noticeDay.plus(7, ChronoUnit.DAYS));
        xlRental.setInventoryRemovalDeadlineAt(InventoryTimelinePolicy.removalDeadline(noticeDay, 10));
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(xlRental));

        Instant day8 = LocalDate.of(2026, 11, 28).atTime(10, 0).atZone(zone).toInstant();
        Instant day11 = LocalDate.of(2026, 12, 1).atTime(0, 0).atZone(zone).toInstant();
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(day8);
            service.advanceSetupRentals();
            assertThat(xlRental.getInventoryEscalatedAt()).isNull();

            clock.when(Instant::now).thenReturn(day11);
            service.advanceSetupRentals();
            assertThat(xlRental.getInventoryEscalatedAt()).isEqualTo(day11);
            verify(maintenanceTaskRepository, times(2)).saveAndFlush(any());
        }
    }
}
