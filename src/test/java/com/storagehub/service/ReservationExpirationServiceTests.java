package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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

    private ReservationExpirationService service;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        service = new ReservationExpirationService(
            reservationRepository, paymentRepository, notificationService, auditLogService
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
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
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
}
