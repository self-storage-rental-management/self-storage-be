package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void createsHighPriorityRecoveryTaskAfterInventoryFinalDeadline() {
        when(rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)).thenReturn(List.of());
        when(rentalRepository.findByInventoryPendingTrueAndStatusIn(any())).thenReturn(List.of(rental));
        when(maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(maintenanceTaskRepository.saveAndFlush(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.advanceSetupRentals();

        assertThat(rental.getInventoryEscalatedAt()).isNotNull();
        var task = org.mockito.ArgumentCaptor.forClass(MaintenanceTask.class);
        org.mockito.Mockito.verify(maintenanceTaskRepository).saveAndFlush(task.capture());
        assertThat(task.getValue().getRecoveryType()).isEqualTo(MaintenanceRecoveryType.INVENTORY_OVERDUE);
        assertThat(task.getValue().getPriority()).isEqualTo(com.storagehub.domain.model.TaskPriority.high);
        assertThat(task.getValue().getDeadlineAt()).isAfter(Instant.now());
    }
}
