package com.storagehub.service;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntakeDeadlineSchedulerTests {
    RentalRepository rentals=mock(RentalRepository.class);
    CheckInRepository checkins=mock(CheckInRepository.class);
    MaintenanceTaskRepository tasks=mock(MaintenanceTaskRepository.class);
    ReturnCaseRepository returns=mock(ReturnCaseRepository.class);
    NotificationService notifications=mock(NotificationService.class);
    UserRepository users=mock(UserRepository.class);
    IntakeDeadlineScheduler scheduler=new IntakeDeadlineScheduler(rentals,checkins,tasks,returns,notifications,users);
    Rental rental() {
        var r=new Rental(); var user=mock(User.class);when(user.getId()).thenReturn(UUID.randomUUID());r.setCustomer(user);
        var f=mock(Facility.class);when(f.getId()).thenReturn(UUID.randomUUID());r.setFacility(f);
        var reservation=mock(Reservation.class);when(reservation.getId()).thenReturn(UUID.randomUUID());r.setReservation(reservation);
        r.setStorageUnit(new StorageUnit());when(rentals.findAll()).thenReturn(List.of(r));return r;
    }
    @Test void oldRecordsWithoutNewDeadlineAreNotReclaimed() {
        rental(); scheduler.process(); verify(tasks,never()).saveAndFlush(any());
    }
    @Test void secondExtensionDoesNotRecoverBeforeFinalDeadline() {
        var r=rental();r.setSetupExtensionCount(2);r.setSetupCompletionDeadlineAt(Instant.now().plusSeconds(3600));
        scheduler.process();verify(tasks,never()).saveAndFlush(any());
    }
    @Test void expiredSetupCreatesPhysicalTaskWithTwentyFourHourDeadline() {
        var r=rental();r.setSetupCompletionDeadlineAt(Instant.now().minusSeconds(1));
        scheduler.process();var capture=ArgumentCaptor.forClass(MaintenanceTask.class);verify(tasks).saveAndFlush(capture.capture());
        var task=capture.getValue();assertThat(task.getRecoveryType()).isEqualTo(MaintenanceRecoveryType.SETUP_OVERDUE);
        assertThat(task.getDeadlineAt()).isAfter(Instant.now().plusSeconds(86300));
        assertThat(r.getActualReturnedAt()).isNull();assertThat(r.getCompletedAt()).isNull();
    }
    @Test void excessTaskDoesNotEndRentalOrRequireWholeUnitEmpty() {
        var r=rental();r.setMoveInCompletedAt(Instant.now().minusSeconds(400000));r.setInventoryResultPublishedAt(Instant.now().minusSeconds(300000));
        r.setInventoryRemainderDeadlineAt(Instant.now().minusSeconds(1));
        var c=new CheckIn();c.setInventoryResolutionStatus(InventoryResolutionStatus.PARTIAL_ACCEPTED);
        when(checkins.findByReservation_Id(any())).thenReturn(Optional.of(c));
        scheduler.process();var capture=ArgumentCaptor.forClass(MaintenanceTask.class);verify(tasks).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getTaskType()).isEqualTo(MaintenanceTaskType.inventory_check);
        assertThat(capture.getValue().getRecoveryType()).isEqualTo(MaintenanceRecoveryType.EXCESS_ITEMS);
        assertThat(r.getInventoryEscalatedAt()).isNull();assertThat(r.getStatus()).isEqualTo(RentalStatus.active);
    }
}
