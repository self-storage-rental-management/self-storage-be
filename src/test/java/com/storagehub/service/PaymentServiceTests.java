package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.config.PaymentProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.PaymentWebhookEventRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTests {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentWebhookEventRepository webhookEventRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationPricingSnapshotRepository snapshotRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    private PaymentService service;
    private PaymentProperties properties;
    private ActorPrincipal actor;
    private User customer;
    private Reservation reservation;
    private ReservationPricingSnapshot snapshot;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.setWebhookSecret("test-webhook-secret");
        service = new PaymentService(
            paymentRepository, webhookEventRepository, reservationRepository, snapshotRepository,
            userRepository, properties, new ObjectMapper(), auditLogService
        );

        customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        actor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
        reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setCustomer(customer);
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        reservation.setHoldExpiresAt(Instant.now().plusSeconds(600));

        snapshot = new ReservationPricingSnapshot();
        snapshot.setReservation(reservation);
        snapshot.setReservationDepositAmount(new BigDecimal("6402000.00"));
    }

    @Test
    void createsDepositUsingAmountStoredInSnapshot() {
        when(paymentRepository.findByIdempotencyKey("payment-1")).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(customer));
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));
        when(paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        )).thenReturn(Optional.empty());
        when(snapshotRepository.findByReservation_Id(reservation.getId()))
            .thenReturn(Optional.of(snapshot));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(payment, "createdAt", Instant.now());
            return payment;
        });

        var response = service.createReservationDepositIntent(
            actor, reservation.getId(), "payment-1"
        );

        assertThat(response.amount()).isEqualByComparingTo("6402000.00");
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.purpose()).isEqualTo(PaymentType.RESERVATION_DEPOSIT);
        assertThat(response.provider()).isEqualTo("MOMO");
    }

    @Test
    void rejectsPaymentBeforeEmailAndGoodsReviewAreComplete() {
        reservation.setStatus(ReservationStatus.AWAITING_REVIEW);
        when(paymentRepository.findByIdempotencyKey("payment-2")).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(customer));
        when(reservationRepository.findByIdAndCustomer_Id(reservation.getId(), actor.userId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.createReservationDepositIntent(
            actor, reservation.getId(), "payment-2"
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation is not awaiting payment");
    }

    @Test
    void paidWebhookConfirmsReservation() throws Exception {
        Payment payment = payment(PaymentStatus.PENDING);
        String body = webhookBody(payment);
        when(webhookEventRepository.findByProviderAndProviderEventId("MOMO", "event-1"))
            .thenReturn(Optional.empty());
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(payment)).thenReturn(payment);

        var response = service.handleWebhook("MOMO", signature(body), body);

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getDepositPaidAt()).isNotNull();
    }

    @Test
    void latePaidWebhookRequiresReconciliation() throws Exception {
        reservation.setHoldExpiresAt(Instant.now().minusSeconds(1));
        Payment payment = payment(PaymentStatus.PENDING);
        String body = webhookBody(payment);
        when(webhookEventRepository.findByProviderAndProviderEventId("MOMO", "event-1"))
            .thenReturn(Optional.empty());
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(payment)).thenReturn(payment);

        var response = service.handleWebhook("MOMO", signature(body), body);

        assertThat(response.status()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(payment, "createdAt", Instant.now());
        payment.setInitiatedBy(customer);
        payment.setReservation(reservation);
        payment.setAmount(new BigDecimal("6402000.00"));
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(status);
        payment.setIdempotencyKey("payment-webhook");
        payment.setProvider("MOMO");
        payment.setGatewayIntentId("pi_test");
        return payment;
    }

    private String webhookBody(Payment payment) {
        return "{\"eventId\":\"event-1\",\"paymentId\":\"" + payment.getId()
            + "\",\"status\":\"PAID\",\"amount\":6402000.00}";
    }

    private String signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"
        ));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
