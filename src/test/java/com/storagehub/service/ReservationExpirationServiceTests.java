package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;
    @Mock private VnpayService vnpayService;

    private ReservationExpirationService service;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        service = new ReservationExpirationService(
            reservationRepository, paymentRepository, notificationService, auditLogService, vnpayService
        );

        User customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());

        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setReservationCode("RSV-EXPIRED001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        reservation.setHoldExpiresAt(Instant.now().minusSeconds(10));
    }

    @Test
    void expiresReservationAndItsPendingPayment() {
        reservation.setStatus(ReservationStatus.AWAITING_EMAIL);
        Payment payment = new Payment();
        payment.setReservation(reservation);
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository
            .findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                anyCollection(), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(
            any(), anyCollection()
        )).thenReturn(List.of(payment));

        int expired = service.expireDueReservations();

        assertThat(expired).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getExpiredAt()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(notificationService).createNotification(
            any(), any(), any(), any(), any()
        );
    }

    @Test
    void doesNothingWhenNoReservationIsDue() {
        when(reservationRepository
            .findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                anyCollection(), any()
            )).thenReturn(List.of());

        int expired = service.expireDueReservations();

        assertThat(expired).isZero();
        verify(paymentRepository, never()).saveAll(any());
        verify(notificationService, never()).createNotification(
            any(), any(), any(), any(), any()
        );
    }

    @Test
    void doesNotExpireReservationWhilePaymentIsProcessing() {
        reservation.setStatus(ReservationStatus.AWAITING_REVIEW);
        Payment payment = new Payment();
        payment.setReservation(reservation);
        payment.setStatus(PaymentStatus.PROCESSING);
        when(reservationRepository
            .findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                anyCollection(), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(
            any(), anyCollection()
        )).thenReturn(List.of(payment));

        int expired = service.expireDueReservations();

        assertThat(expired).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.AWAITING_REVIEW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).createNotification(
            any(), any(), any(), any(), any()
        );
    }

    @Test
    void startsPaymentGraceWhenVnpayAttemptHasNoFinalResult() {
        reservation.setPaymentExpiresAt(Instant.now().minusSeconds(5));
        Payment payment = new Payment();
        payment.setReservation(reservation);
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository
            .findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
                eq(ReservationStatus.AWAITING_PAYMENT), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(any(), anyCollection()))
            .thenReturn(List.of(payment));
        when(vnpayService.reconcileAtPaymentDeadline(payment))
            .thenReturn(VnpayService.ReconciliationOutcome.UNKNOWN);

        int transitioned = service.expireDueReservations();

        assertThat(transitioned).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_GRACE);
        assertThat(reservation.getComplaintExpiresAt()).isAfter(Instant.now());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NOT_RECEIVED);
        assertThat(payment.getFailureCode()).isEqualTo("VNPAY_RESULT_NOT_RECEIVED");
        verify(paymentRepository).saveAll(List.of(payment));
        verify(notificationService).createNotification(any(), any(), any(), any(), any());
    }

    @Test
    void expiresWithoutComplaintWhenCustomerNeverStartedPayment() {
        reservation.setPaymentExpiresAt(Instant.now().minusSeconds(5));
        when(reservationRepository
            .findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
                eq(ReservationStatus.AWAITING_PAYMENT), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(any(), anyCollection()))
            .thenReturn(List.of());

        service.expireDueReservations();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getComplaintExpiresAt()).isNull();
        assertThat(reservation.getArchivedAt()).isNotNull();
        verify(notificationService).createNotification(any(), any(), any(), any(), any());
    }

    @Test
    void confirmsReservationWhenDeadlineQueryFindsSuccessfulPayment() {
        reservation.setPaymentExpiresAt(Instant.now().minusSeconds(5));
        Payment payment = new Payment();
        payment.setReservation(reservation);
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository
            .findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
                eq(ReservationStatus.AWAITING_PAYMENT), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(any(), anyCollection()))
            .thenReturn(List.of(payment));
        when(vnpayService.reconcileAtPaymentDeadline(payment)).thenAnswer(invocation -> {
            payment.setStatus(PaymentStatus.PAID);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            return VnpayService.ReconciliationOutcome.SUCCESS;
        });

        service.expireDueReservations();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getComplaintExpiresAt()).isNull();
    }

    @Test
    void expiresWithoutGraceWhenDeadlineQueryConfirmsFailure() {
        reservation.setPaymentExpiresAt(Instant.now().minusSeconds(5));
        Payment payment = new Payment();
        payment.setReservation(reservation);
        payment.setStatus(PaymentStatus.PENDING);
        when(reservationRepository
            .findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
                eq(ReservationStatus.AWAITING_PAYMENT), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(any(), anyCollection()))
            .thenReturn(List.of(payment), List.of());
        when(vnpayService.reconcileAtPaymentDeadline(payment)).thenAnswer(invocation -> {
            payment.setStatus(PaymentStatus.FAILED);
            return VnpayService.ReconciliationOutcome.FINAL_FAILURE;
        });

        service.expireDueReservations();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getComplaintExpiresAt()).isNull();
    }

    @Test
    void expiresPaymentGraceWhenComplaintWindowEnds() {
        reservation.setStatus(ReservationStatus.PAYMENT_GRACE);
        reservation.setComplaintExpiresAt(Instant.now().minusSeconds(5));
        when(reservationRepository
            .findTop100ByStatusAndComplaintExpiresAtLessThanEqualOrderByComplaintExpiresAtAsc(
                eq(ReservationStatus.PAYMENT_GRACE), any()
            )).thenReturn(List.of(reservation));
        when(paymentRepository.findAllByReservation_IdAndStatusIn(any(), anyCollection()))
            .thenReturn(List.of());

        int expired = service.expireDueReservations();

        assertThat(expired).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getArchivedAt()).isNotNull();
    }
}
