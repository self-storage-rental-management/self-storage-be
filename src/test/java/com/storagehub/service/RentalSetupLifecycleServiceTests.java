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
            any(String.class), org.mockito.ArgumentMatchers.eq(rental.getReservation().getId()));
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
}
