package com.storagehub.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeWorkflowServiceTests {
    RentalRepository rentals = mock(RentalRepository.class);
    CheckInRepository checkins = mock(CheckInRepository.class);
    FileAssetRepository files = mock(FileAssetRepository.class);
    FacilityScopeService scope = mock(FacilityScopeService.class);
    NotificationService notifications = mock(NotificationService.class);
    ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    IntakeWorkflowService service = new IntakeWorkflowService(rentals, checkins, files, scope, notifications, new ObjectMapper(), returns);
    UUID reservationId = UUID.randomUUID(), customerId = UUID.randomUUID();
    Rental rental = new Rental();
    ActorPrincipal customer = actor(customerId, RoleCode.CUSTOMER);
    static ActorPrincipal actor(UUID id, RoleCode role) { return new ActorPrincipal(id, UUID.randomUUID(), Set.of(role), Set.of(), Map.of()); }
    @BeforeEach void prepare() {
        var user = mock(User.class); when(user.getId()).thenReturn(customerId); rental.setCustomer(user);
        var facility = mock(Facility.class); when(facility.getId()).thenReturn(UUID.randomUUID()); rental.setFacility(facility);
        var reservation = mock(Reservation.class); when(reservation.getId()).thenReturn(reservationId); rental.setReservation(reservation);
        when(rentals.findByReservationIdForUpdate(reservationId)).thenReturn(Optional.of(rental));
        when(rentals.findByReservation_Id(reservationId)).thenReturn(Optional.of(rental));
        rental.setSetupCompletionDeadlineAt(Instant.now().plusSeconds(86400));
        rental.setSetupExtensionDays(2); rental.setSetupExtensionCount(0);
    }
    @Test void retryDoesNotConsumeSecondExtensionAndThirdIsRejected() {
        Instant first = rental.getSetupCompletionDeadlineAt().plusSeconds(86400);
        var cmd = new IntakeWorkflowService.Command("EXTEND_SETUP", first, null, null, null);
        service.change(customer, reservationId, cmd); service.change(customer, reservationId, cmd);
        assertThat(rental.getSetupExtensionCount()).isEqualTo(1);
        Instant second = first.plusSeconds(86400);
        service.change(customer, reservationId, new IntakeWorkflowService.Command("EXTEND_SETUP", second, null, null, null));
        assertThat(rental.getSetupExtensionCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.change(customer, reservationId, new IntakeWorkflowService.Command("EXTEND_SETUP", second.plusSeconds(1), null, null, null))).isInstanceOf(RuntimeException.class);
    }
    @Test void foreignCustomerCannotReadOrMutate() {
        var foreign = actor(UUID.randomUUID(), RoleCode.CUSTOMER);
        assertThatThrownBy(() -> service.read(foreign, reservationId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.change(foreign, reservationId, new IntakeWorkflowService.Command("EXTEND_SETUP", Instant.now(), null, null, null))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(notifications);
    }
    @Test void managerCannotRecordArrivalOrVerifyIdentity() {
        var manager = actor(UUID.randomUUID(), RoleCode.MANAGER);
        assertThatThrownBy(() -> service.change(manager, reservationId, new IntakeWorkflowService.Command("ARRIVAL", null, null, null, null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.change(manager, reservationId, new IntakeWorkflowService.Command("VERIFY", null, null, "123", UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
        assertThat(rental.getInventoryArrivedAt()).isNull();
    }
    @Test void appointmentCannotCrossFinalDeadlineAndDoesNotResetClock() {
        Instant finalDeadline=Instant.now().plusSeconds(172800); rental.setMoveInCompletedAt(Instant.now()); rental.setInventoryFinalDeadlineAt(finalDeadline);
        assertThatThrownBy(() -> service.change(customer, reservationId, new IntakeWorkflowService.Command("APPOINTMENT", finalDeadline, null, null, null))).isInstanceOf(RuntimeException.class);
        service.change(customer, reservationId, new IntakeWorkflowService.Command("APPOINTMENT", finalDeadline.minusSeconds(3600), null, null, null));
        assertThat(rental.getInventoryFinalDeadlineAt()).isEqualTo(finalDeadline);
        assertThat(rental.getWorkflowHistoryJson()).contains("APPOINTMENT");
    }
    @Test void staffAndManagerHaveIndependentReminderCooldowns() {
        var staff = actor(UUID.randomUUID(), RoleCode.STAFF); var manager=actor(UUID.randomUUID(), RoleCode.MANAGER);
        var cmd = new IntakeWorkflowService.Command("REMIND", null, null, null, null);
        service.change(staff, reservationId, cmd); service.change(manager, reservationId, cmd);
        assertThatThrownBy(() -> service.change(staff, reservationId, cmd)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.change(manager, reservationId, cmd)).isInstanceOf(RuntimeException.class);
        assertThat(rental.getWorkflowHistoryJson()).contains("REMIND_STAFF", "REMIND_MANAGER");
    }
}
