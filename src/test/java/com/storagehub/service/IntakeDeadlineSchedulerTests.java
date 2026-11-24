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
    @Test void expiredInventoryCreatesRecoveryOnlyWhenCustomerHasNotArrived() {
        var r = rental();
        r.setStatus(RentalStatus.active);
        r.setInventoryPending(true);
        r.setMoveInCompletedAt(Instant.now().minusSeconds(8 * 86400L));
        r.setInventoryFinalDeadlineAt(Instant.now().minusSeconds(1));
        scheduler.process();
        var capture = ArgumentCaptor.forClass(MaintenanceTask.class);
        verify(tasks).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getRecoveryType()).isEqualTo(MaintenanceRecoveryType.INVENTORY_OVERDUE);
        assertThat(r.getInventoryEscalatedAt()).isNotNull();
        scheduler.process();
        verify(tasks, times(1)).saveAndFlush(any());
    }

    @Test void customerArrivalBeforeDeadlineDoesNotCreateRecovery() {
        var r = rental();
        r.setStatus(RentalStatus.active);
        r.setInventoryPending(true);
        r.setMoveInCompletedAt(Instant.now().minusSeconds(8 * 86400L));
        r.setInventoryFinalDeadlineAt(Instant.now().minusSeconds(1));
        r.setInventoryArrivedAt(r.getInventoryFinalDeadlineAt().minusSeconds(60));
        scheduler.process();
        verify(tasks, never()).saveAndFlush(any());
        InventoryDeadlinePolicy.requireInventoryOpen(r, Instant.now());
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
    @Test void retiredPartialIntakeDoesNotCreateExcessRemovalTasks() {
        var r=rental();r.setMoveInCompletedAt(Instant.now().minusSeconds(400000));r.setInventoryResultPublishedAt(Instant.now().minusSeconds(300000));
        r.setInventoryRemainderDeadlineAt(Instant.now().minusSeconds(1));
        var c=new CheckIn();c.setInventoryResolutionStatus(InventoryResolutionStatus.PARTIAL_ACCEPTED);
        when(checkins.findByReservation_Id(any())).thenReturn(Optional.of(c));
        scheduler.process();
        verify(tasks, never()).saveAndFlush(any());
        org.mockito.Mockito.verifyNoInteractions(notifications);
        assertThat(r.getInventoryEscalatedAt()).isNull();assertThat(r.getStatus()).isEqualTo(RentalStatus.active);
    }
    @Test void alreadyEmptyUnitNeverGetsAnotherGoodsMovementTask() {
        // The unit was handed back empty but finance is still open. "Unit empty" and
        // "settlement finished" are separate states, so no new recovery task may be created.
        var r=rental();
        r.setInventoryRemovalDeadlineAt(Instant.now().minusSeconds(1));
        r.setActualReturnedAt(Instant.now().minusSeconds(86400));
        r.setStatus(RentalStatus.return_requested);
        scheduler.process();
        verify(tasks,never()).saveAndFlush(any());
    }
    @Test void emptyUnitBeforeRemovalDeadlineAlsoCreatesNoTask() {
        var r=rental();
        r.setInventoryRemovalDeadlineAt(Instant.now().plusSeconds(3600));
        r.setActualReturnedAt(Instant.now());
        r.setStatus(RentalStatus.return_requested);
        scheduler.process();
        verify(tasks,never()).saveAndFlush(any());
    }
    @Test void atSiteResolutionDoesNotStartTheFourtyEightHourClock() {
        // The verified customer is present and the arrangement was recorded at the facility.
        // No online confirmation and therefore no 48-hour reminder may be started.
        var r=rental();
        r.setInventoryResultPublishedAt(Instant.now().minusSeconds(5*86400));
        var c=new CheckIn();
        c.setInventoryResolutionStatus(InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        when(checkins.findByReservation_Id(any())).thenReturn(Optional.of(c));
        scheduler.process();
        verify(notifications,never()).createNotificationOnce(any(),any(),eq("Đã quá hạn xác nhận kiểm kê"),any(),any());
        verify(notifications,never()).createNotificationOnce(any(),any(),eq("Nhắc xác nhận kết quả kiểm kê"),any(),any());
    }
    @Test void onlineConfirmationStillGetsTheFourtyEightHourEscalation() {
        var r=rental();
        r.setInventoryResultPublishedAt(Instant.now().minusSeconds(5*86400));
        var c=new CheckIn();
        c.setInventoryResolutionStatus(InventoryResolutionStatus.PENDING_CUSTOMER);
        when(checkins.findByReservation_Id(any())).thenReturn(Optional.of(c));
        scheduler.process();
        verify(notifications).createNotificationOnce(any(),any(),eq("Đã quá hạn xác nhận kiểm kê"),any(),any());
    }
}
