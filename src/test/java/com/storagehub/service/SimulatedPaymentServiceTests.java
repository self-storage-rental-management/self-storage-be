package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.config.PaymentProperties;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentSimulationOutcome;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SimulatedPaymentServiceTests {

    @Mock private PaymentRepository paymentRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    private PaymentProperties properties;
    private SimulatedPaymentService service;
    private ActorPrincipal actor;
    private User customer;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        service = new SimulatedPaymentService(
            paymentRepository, reservationRepository, snapshotRepository, userRepository,
            properties, auditLogService
        );
        customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        reservation.setPaymentExpiresAt(Instant.now().plusSeconds(600));

        ReservationPricingSnapshot snapshot = new ReservationPricingSnapshot();
        snapshot.setReservation(reservation);
        snapshot.setReservationDepositAmount(new BigDecimal("400000.00"));
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(reservationRepository.findOwnedByIdForUpdate(reservation.getId(), customer.getId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(Optional.empty());
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(payment, "createdAt", Instant.now());
            return payment;
        });
    }

    @Test
    void successUsesSnapshotAndConfirmsReservation() {
        properties.setSimulationOutcome(PaymentSimulationOutcome.SUCCESS);

        var response = service.pay(actor, reservation.getId(), "sim-success");

        assertThat(response.outcome()).isEqualTo(PaymentSimulationOutcome.SUCCESS);
        assertThat(response.amount()).isEqualByComparingTo("400000.00");
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getDepositPaidAt()).isNotNull();
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    void failedPaymentKeepsReservationAwaitingPayment() {
        properties.setSimulationOutcome(PaymentSimulationOutcome.FAILED);

        var response = service.pay(actor, reservation.getId(), "sim-failed");

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        verify(reservationRepository, never()).saveAndFlush(reservation);
    }

    @Test
    void notReceivedKeepsReservationAwaitingPayment() {
        properties.setSimulationOutcome(PaymentSimulationOutcome.NOT_RECEIVED);

        var response = service.pay(actor, reservation.getId(), "sim-not-received");

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.NOT_RECEIVED);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
    }

    @Test
    void preventsPayingReservationOwnedByAnotherCustomer() {
        reset(paymentRepository, reservationRepository, snapshotRepository, userRepository, auditLogService);
        ActorPrincipal otherCustomer = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        assertThatThrownBy(() -> service.pay(otherCustomer, reservation.getId(), "ownership-test"))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(404))
            .hasMessage("Reservation was not found");
    }
}
