package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.CancelReservationRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationQuote;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CustomerReservationServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private ReservationGoodsItemRepository goodsItemRepository;
    @Mock private AuditLogService auditLogService;

    private CustomerReservationService service;
    private ActorPrincipal actor;
    private Reservation reservation;
    private ReservationPricingSnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = new CustomerReservationService(
            reservationRepository, paymentRepository, snapshotRepository,
            goodsItemRepository, auditLogService
        );

        User customer = entityWithId(new User());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        Facility facility = entityWithId(new Facility());
        UnitType unitType = entityWithId(new UnitType());
        ReservationQuote quote = entityWithId(new ReservationQuote());

        reservation = entityWithId(new Reservation());
        ReflectionTestUtils.setField(reservation, "createdAt", Instant.now());
        reservation.setReservationCode("RSV-TEST000001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setSourceQuote(quote);
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        reservation.setGoodsReviewStatus(GoodsReviewStatus.NOT_REQUIRED);
        reservation.setCompatibilityResult(CompatibilityResult.COMPATIBLE);
        reservation.setStartDate(LocalDate.of(2026, 10, 10));
        reservation.setEndDate(LocalDate.of(2027, 1, 10));
        reservation.setTotalGoodsVolumeM3(new BigDecimal("1.000000"));
        reservation.setTotalGoodsWeightKg(new BigDecimal("20.00"));
        reservation.setHoldExpiresAt(Instant.now().plusSeconds(600));

        snapshot = new ReservationPricingSnapshot();
        snapshot.setNetRentalAmount(new BigDecimal("16005000.00"));
        snapshot.setReservationDepositAmount(new BigDecimal("6402000.00"));
        snapshot.setSecurityDepositAmount(new BigDecimal("5500000.00"));
        snapshot.setRemainingRentalAmount(new BigDecimal("9603000.00"));
        snapshot.setDueAtCheckIn(new BigDecimal("15103000.00"));
        snapshot.setTotalInitialObligation(new BigDecimal("21505000.00"));
    }

    @Test
    void listsOnlyReservationsOwnedByCustomer() {
        when(reservationRepository.findAllByCustomer_IdAndArchivedAtIsNull(any(), any()))
            .thenReturn(new PageImpl<>(List.of(reservation)));
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));

        var response = service.list(actor, null, 0, 20, "correlation-id");

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).getId()).isEqualTo(reservation.getId());
        verify(reservationRepository).findAllByCustomer_IdAndArchivedAtIsNull(any(), any());
    }

    @Test
    void cancelsReservationWhileItIsWaiting() {
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of());
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());

        var response = service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Không còn nhu cầu")
        );

        assertThat(response.getReservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.getCancelReason()).isEqualTo("Không còn nhu cầu");
        assertThat(response.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelsConfirmedReservationWithoutRefundingPaidDepositOrReleasingAssignedUnit() {
        reservation.setStatus(ReservationStatus.CONFIRMED);
        StorageUnit assignedUnit = entityWithId(new StorageUnit());
        assignedUnit.setStatus(StorageUnitStatus.assigned);
        reservation.setAssignedUnit(assignedUnit);
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PAID);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());

        service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        );

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getAssignedUnit()).isSameAs(assignedUnit);
        assertThat(assignedUnit.getStatus()).isEqualTo(StorageUnitStatus.assigned);
    }

    @Test
    void rejectsCancellationWhilePaymentIsProcessing() {
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PROCESSING);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));

        assertThatThrownBy(() -> service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("payment is processing");
    }

    @Test
    void rejectsCancellationAfterCheckInFlowHasStarted() {
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Đổi kế hoạch")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void cancelsPendingPaymentWhenReservationIsCancelled() {
        Payment payment = new Payment();
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(List.of(payment));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of());

        service.cancel(
            actor, reservation.getId(), new CancelReservationRequest("Không còn nhu cầu")
        );

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void hidesReservationNotOwnedByCustomer() {
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(actor, reservation.getId()))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation was not found");
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
