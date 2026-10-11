package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Regression for the sole shared-writer change: extra audit metadata, same handoff behavior. */
class CustomerReceiptPeriodCompatibilityTests {
    ReservationRepository reservations;RentalRepository rentals;CheckInRepository checkIns;StorageUnitRepository units;
    AuditLogService audit;NotificationService notifications;CustomerReservationService service;
    Reservation reservation;CheckIn checkIn;ActorPrincipal actor;
    @BeforeEach void setup() {
        reservations=mock(ReservationRepository.class);rentals=mock(RentalRepository.class);
        checkIns=mock(CheckInRepository.class);units=mock(StorageUnitRepository.class);
        audit=mock(AuditLogService.class);notifications=mock(NotificationService.class);
        var snapshots=mock(ReservationPricingSnapshotRepository.class);var goods=mock(ReservationGoodsItemRepository.class);
        service=new CustomerReservationService(reservations,checkIns,mock(PaymentRepository.class),rentals,units,snapshots,goods,audit,notifications);
        var user=id(new User());var f=id(new Facility());var unit=id(new StorageUnit());unit.setFacility(f);
        reservation=id(new Reservation());reservation.setCustomer(user);reservation.setFacility(f);reservation.setAssignedUnit(unit);
        reservation.setUnitType(id(new UnitType()));reservation.setSourceQuote(id(new ReservationQuote()));
        reservation.setStartDate(LocalDate.of(2026,10,1));reservation.setEndDate(LocalDate.of(2026,11,1));
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        checkIn=id(new CheckIn());checkIn.setReservation(reservation);checkIn.setStatus(CheckInStatus.completed);
        var snapshot=new ReservationPricingSnapshot();snapshot.setMonthlyPrice(new BigDecimal("123456"));
        actor=new ActorPrincipal(user.getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());
        when(reservations.findOwnedByIdForUpdate(reservation.getId(),actor.userId())).thenReturn(Optional.of(reservation));
        when(checkIns.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(checkIn));
        when(snapshots.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(reservations.saveAndFlush(reservation)).thenReturn(reservation);
        when(rentals.saveAndFlush(any())).thenAnswer(a -> id(a.getArgument(0)));
        when(goods.findAllByReservation_IdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }
    <T extends BaseEntity> T id(T entity) {ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());return entity;}
    @SuppressWarnings("unchecked") Map<String,Object> evidence(Rental rental) {
        var after=ArgumentCaptor.forClass(Object.class);
        verify(audit).recordMutation(eq(reservation.getCustomer()),eq("CUSTOMER_RECEIPT_CONFIRMED"),eq("Reservation"),
            eq(reservation.getId()),eq(reservation.getFacility().getId()),any(),after.capture());
        var payload=(Map<String,Object>)after.getValue();
        assertThat(payload).containsEntry("status",ReservationStatus.COMPLETED).containsEntry("rentalId",rental.getId());
        return (Map<String,Object>)payload.get("rentalPeriodEvidence");
    }
    @Test void newReceiptPreservesRawDatesAppliedPriceAndAllSideEffects() {
        var response=service.confirmReceipt(actor,reservation.getId());var rental=checkIn.getRental();
        assertThat(response.getReservation().getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(rental.getStartDate()).isEqualTo(reservation.getStartDate());
        assertThat(rental.getContractEndDate()).isEqualTo(reservation.getEndDate());
        assertThat(rental.getMonthlyPrice()).isEqualByComparingTo("123456");
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.active);
        assertThat(reservation.getAssignedUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(evidence(rental)).containsEntry("rentalCreated",true).containsEntry("convention","EXCLUSIVE")
            .containsEntry("storedEndDate","2026-11-01");
        verify(rentals,times(1)).saveAndFlush(any());verify(checkIns).saveAndFlush(checkIn);
        verify(units).saveAndFlush(reservation.getAssignedUnit());verify(notifications).createNotification(any(),eq(NotificationType.CHECKIN),anyString(),anyString(),eq(reservation.getId()));
    }
    @Test void reusePreservesLegacyRentalDatesMoneyAndIdentityWithoutClaimingConvention() {
        var existing=id(new Rental());existing.setStartDate(LocalDate.of(2026,9,10));existing.setContractEndDate(LocalDate.of(2026,10,31));
        existing.setMonthlyPrice(new BigDecimal("999"));
        when(rentals.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(existing));
        service.confirmReceipt(actor,reservation.getId());
        assertThat(checkIn.getRental()).isSameAs(existing);assertThat(existing.getContractEndDate()).isEqualTo(LocalDate.of(2026,10,31));
        assertThat(existing.getMonthlyPrice()).isEqualByComparingTo("999");verify(rentals,never()).saveAndFlush(any());
        assertThat(evidence(existing)).containsEntry("rentalCreated",false).containsEntry("convention","UNKNOWN");
    }
    @Test void completedReplayDoesNotCreateSecondRentalOrAudit() {
        reservation.setStatus(ReservationStatus.COMPLETED);service.confirmReceipt(actor,reservation.getId());
        verifyNoInteractions(rentals,audit,notifications,units);verify(checkIns,never()).saveAndFlush(any());
    }
    @Test void pendingHandoverRemainsBlockedWithoutMutations() {
        checkIn.setStatus(CheckInStatus.scheduled);
        assertThatThrownBy(() -> service.confirmReceipt(actor,reservation.getId())).hasMessageContaining("handover is not completed");
        verifyNoInteractions(rentals,audit,notifications,units);
    }
    @Test void wrongRoleRemainsDeniedBeforeReadingOrWriting() {
        var staff=new ActorPrincipal(actor.userId(),actor.sessionId(),Set.of(RoleCode.STAFF),Set.of(),Map.of());
        assertThatThrownBy(() -> service.confirmReceipt(staff,reservation.getId())).hasMessageContaining("Only customer");
        verifyNoInteractions(rentals,audit,notifications,units,reservations);
    }
}
