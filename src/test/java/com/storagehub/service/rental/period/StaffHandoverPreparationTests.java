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
class StaffHandoverPreparationTests {
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
    ReservationPricingSnapshot snapshot;
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
        snapshot = id(new ReservationPricingSnapshot());
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


    @Test void staffHandoverStartsPreparationImmediatelyAndReplayDoesNotDuplicateRental() {
        var at=Instant.parse("2026-11-27T03:00:00Z");
        checkIn.setStatus(CheckInStatus.handover_pending_receipt); checkIn.setCheckedInAt(at); checkIn.setRental(null);
        snapshot.setFreeSetupDays(2);
        com.storagehub.testing.AcceptedReservationSnapshotFixture.bind(reservation,snapshot);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(rentals.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.empty());
        when(rentals.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        service.startPreparationAfterHandover(reservation.getId(),at,reservation.getCustomer());
        var rental=checkIn.getRental();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PREPARING);
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_in_progress);
        assertThat(checkIn.getCustomerReceiptConfirmedAt()).isNull();
        assertThat(rental.isStaffHandoverPreparation()).isTrue();
        assertThat(rental.getSetupStartedAt()).isEqualTo(at);
        assertThat(rental.getSetupEndsAt()).isEqualTo(at.plusSeconds(2*86400));
        assertThat(rental.getStartDate()).isEqualTo(LocalDate.of(2026,11,29));
        assertThat(reservation.getAssignedUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        service.startPreparationAfterHandover(reservation.getId(),at.plusSeconds(60),reservation.getCustomer());
        verify(rentals,times(1)).saveAndFlush(any());
        assertThat(checkIn.getSetupStartedAt()).isEqualTo(at);
    }
    @Test void customerConfirmationEndpointIsRetiredWithoutWrites() {
        assertThatThrownBy(()->service.confirmReceipt(actor,reservation.getId())).hasMessageContaining("Không còn bước");
        verify(reservations,never()).saveAndFlush(any());
        verify(rentals,never()).saveAndFlush(any());
    }
    @Test void retiredReleaseEndpointCannotFreeAPaidUnit() {
        var handover=mock(CheckInHandoverService.class,CALLS_REAL_METHODS);
        assertThatThrownBy(()->handover.releaseAfterCustomerReceiptTimeout(actor,checkIn.getId())).hasMessageContaining("Đã bỏ giải phóng");
        assertThat(reservation.getAssignedUnit()).isNotNull();
    }
}
