package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.*;
import com.storagehub.service.ledger.LedgerStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** Receipt acknowledges an existing verified handover; it cannot invent a Rental or ledger. */
class CustomerReceiptPeriodCompatibilityTests {
    ReservationRepository reservations;
    RentalRepository rentals;
    CheckInRepository checkIns;
    StorageUnitRepository units;
    AuditLogService audit;
    NotificationService notifications;
    LedgerStore ledger;
    ObjectProvider<LedgerStore> ledgerStores;
    CustomerReservationService service;
    Reservation reservation;
    CheckIn checkIn;
    ActorPrincipal actor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        reservations = mock(ReservationRepository.class);
        rentals = mock(RentalRepository.class);
        checkIns = mock(CheckInRepository.class);
        units = mock(StorageUnitRepository.class);
        audit = mock(AuditLogService.class);
        notifications = mock(NotificationService.class);
        ledger = mock(LedgerStore.class);
        ledgerStores = mock(ObjectProvider.class);
        when(ledgerStores.getIfAvailable()).thenReturn(ledger);
        var snapshots = mock(ReservationPricingSnapshotRepository.class);
        var goods = mock(ReservationGoodsItemRepository.class);
        service = new CustomerReservationService(reservations, mock(PaymentRepository.class), checkIns,
            rentals, units, mock(com.storagehub.domain.repo.FileAssetRepository.class), snapshots,
            goods, audit, notifications, ledgerStores);

        var user = id(new User());
        var facility = id(new Facility());
        var unit = id(new StorageUnit());
        unit.setFacility(facility);
        reservation = id(new Reservation());
        reservation.setCustomer(user);
        reservation.setFacility(facility);
        reservation.setAssignedUnit(unit);
        reservation.setUnitType(id(new UnitType()));
        reservation.setSourceQuote(id(com.storagehub.testing.LegacyReservationQuoteFixture.create()));
        reservation.setStartDate(LocalDate.of(2026, 10, 1));
        reservation.setEndDate(LocalDate.of(2026, 11, 1));
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        checkIn = id(new CheckIn());
        checkIn.setReservation(reservation);
        checkIn.setStatus(CheckInStatus.completed);
        var snapshot = id(new ReservationPricingSnapshot());
        snapshot.setReservation(reservation);
        snapshot.setPricingPackageCode("TEST-1M");
        snapshot.setPricingPolicyVersion("test-v1");
        snapshot.setRentalMonths(1);
        snapshot.setMonthlyPrice(new BigDecimal("123456"));
        snapshot.setGrossRentalAmount(new BigDecimal("123456"));
        snapshot.setDiscountRate(BigDecimal.ZERO);
        snapshot.setDiscountAmount(BigDecimal.ZERO);
        snapshot.setNetRentalAmount(new BigDecimal("123456"));
        snapshot.setReservationDepositAmount(new BigDecimal("24691.20"));
        snapshot.setSecurityDepositAmount(new BigDecimal("123456"));
        snapshot.setRemainingRentalAmount(new BigDecimal("98764.80"));
        snapshot.setDueAtCheckIn(new BigDecimal("222220.80"));
        snapshot.setTotalInitialObligation(new BigDecimal("246912"));
        com.storagehub.testing.AcceptedReservationSnapshotFixture.bind(reservation,snapshot);
        var handoverAt=Instant.parse("2026-10-01T02:00:00Z");
        var rental=id(new Rental());
        rental.setReservation(reservation);rental.setCustomer(user);rental.setFacility(facility);rental.setStorageUnit(unit);
        rental.setStatus(RentalStatus.active);rental.setStartDate(reservation.getStartDate());rental.setContractEndDate(reservation.getEndDate());
        rental.setMonthlyPrice(snapshot.getMonthlyPrice());rental.setSetupStartedAt(handoverAt);rental.setTermsEffectiveAt(handoverAt);
        rental.setSetupEndsAt(Instant.parse("2026-09-30T17:00:00Z"));rental.setInventoryVerifiedAt(handoverAt.plusSeconds(3600));
        checkIn.setRental(rental);checkIn.setSetupStartedAt(handoverAt);checkIn.setSetupDueAt(rental.getSetupEndsAt());
        checkIn.setContractSignedAt(handoverAt.minusSeconds(120));checkIn.setPaymentCollectedAt(handoverAt.minusSeconds(60));
        checkIn.setInventoryVerifiedAt(rental.getInventoryVerifiedAt());
        when(rentals.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(rental));
        when(rentals.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(rental));
        actor = new ActorPrincipal(user.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());

        when(reservations.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(checkIns.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(checkIn));
        when(snapshots.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(reservations.saveAndFlush(reservation)).thenReturn(reservation);
        when(rentals.saveAndFlush(any())).thenAnswer(invocation -> id(invocation.getArgument(0)));
        when(goods.findAllByReservation_IdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private <T extends BaseEntity> T id(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidence(Rental rental) {
        var after = ArgumentCaptor.forClass(Object.class);
        verify(audit).recordMutation(eq(reservation.getCustomer()), eq("CUSTOMER_RECEIPT_CONFIRMED"),
            eq("Reservation"), eq(reservation.getId()), eq(reservation.getFacility().getId()), any(), after.capture());
        var payload = (Map<String, Object>) after.getValue();
        assertThat(payload).containsEntry("status", ReservationStatus.COMPLETED)
            .containsEntry("rentalId", rental.getId());
        return (Map<String, Object>) payload.get("rentalPeriodEvidence");
    }

    @Test
    void newReceiptPreservesExistingHandoverWithoutCreatingRentalOrLedger() {
        var response = service.confirmReceipt(actor, reservation.getId());
        var rental = checkIn.getRental();
        assertThat(response.getReservation().getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(rental.getStartDate()).isEqualTo(reservation.getStartDate());
        assertThat(rental.getContractEndDate()).isEqualTo(reservation.getEndDate());
        assertThat(rental.getMonthlyPrice()).isEqualByComparingTo("123456");
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.active);
        assertThat(reservation.getAssignedUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(evidence(rental)).containsEntry("rentalCreated", false).containsEntry("convention", "EXCLUSIVE")
            .containsEntry("storedEndDate", "2026-11-01");
        verifyNoInteractions(ledger);
        verify(rentals, never()).saveAndFlush(any());
        verify(checkIns,never()).saveAndFlush(any());
        verify(units).saveAndFlush(reservation.getAssignedUnit());
        verify(notifications).createNotification(any(), eq(NotificationType.CHECKIN), anyString(), anyString(),
            eq(reservation.getId()));
    }

    @Test
    void inconsistentExistingRentalIsBlockedWithoutBackfillingLedgerOrChangingHistoricalTerms() {
        var existing = id(new Rental());
        existing.setStartDate(LocalDate.of(2026, 9, 10));
        existing.setContractEndDate(LocalDate.of(2026, 10, 31));
        existing.setMonthlyPrice(new BigDecimal("999"));
        existing.setCustomer(reservation.getCustomer());
        existing.setFacility(reservation.getFacility());
        when(rentals.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(()->service.confirmReceipt(actor, reservation.getId())).hasMessageContaining("Thiếu hoặc lệch chứng cứ bàn giao/setup");

        assertThat(checkIn.getRental()).isNotSameAs(existing);
        assertThat(existing.getContractEndDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(existing.getMonthlyPrice()).isEqualByComparingTo("999");
        verify(rentals, never()).saveAndFlush(any());
        verifyNoInteractions(ledger);
        verifyNoInteractions(audit,notifications);
    }

    @Test
    void completedCheckInWithoutHandoverRentalCannotInventRentalOrLedger() {
        when(rentals.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.confirmReceipt(actor,reservation.getId()))
            .hasMessageContaining("Thiếu hồ sơ thuê từ bàn giao thực tế");
        verify(rentals,never()).saveAndFlush(any());
        verifyNoInteractions(ledger,units,audit,notifications);
    }

    @Test
    void completedReplayDoesNotCreateRentalOrOpenAnotherLedgerAccount() {
        reservation.setStatus(ReservationStatus.COMPLETED);

        service.confirmReceipt(actor, reservation.getId());

        verify(rentals).findByReservation_Id(reservation.getId());
        verifyNoInteractions(audit, notifications, units, ledger, ledgerStores);
        verify(checkIns, never()).saveAndFlush(any());
    }

    @Test
    void pendingHandoverRemainsBlockedWithoutLedgerWrites() {
        checkIn.setStatus(CheckInStatus.scheduled);

        assertThatThrownBy(() -> service.confirmReceipt(actor, reservation.getId()))
            .hasMessageContaining("handover is not completed");

        verifyNoInteractions(rentals, audit, notifications, units, ledger, ledgerStores);
    }

    @Test
    void wrongRoleRemainsDeniedBeforeReadingOrWriting() {
        var staff = new ActorPrincipal(actor.userId(), actor.sessionId(), Set.of(RoleCode.STAFF), Set.of(), Map.of());

        assertThatThrownBy(() -> service.confirmReceipt(staff, reservation.getId()))
            .hasMessageContaining("Only customer");

        verifyNoInteractions(reservations, rentals, audit, notifications, units, ledger, ledgerStores);
    }
}
