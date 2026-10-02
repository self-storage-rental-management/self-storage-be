package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.payment.CreatePaymentIntentRequest;
import com.storagehub.api.payment.PaymentIntentResponse;
import com.storagehub.api.payment.PaymentWebhookPayload;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.PaymentProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.PaymentWebhookEvent;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.PaymentWebhookEventRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository paymentWebhookEventRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Transactional
    public PaymentIntentResponse createIntent(ActorPrincipal actor, CreatePaymentIntentRequest request, String idempotencyKey) {
        if (request.purpose() != PaymentType.RESERVATION_DEPOSIT
            || !"VND".equalsIgnoreCase(request.currency())) {
            throw ApiExceptions.validation(
                "Booking payment must use VND and RESERVATION_DEPOSIT", null
            );
        }
        PaymentIntentResponse response = createReservationDepositIntent(
            actor, request.reservationId(), idempotencyKey
        );
        if (response.amount().compareTo(request.amount()) != 0) {
            throw ApiExceptions.conflict("Payment amount does not match the reservation deposit");
        }
        return response;
    }

    @Transactional
    public PaymentIntentResponse createReservationDepositIntent(
        ActorPrincipal actor,
        UUID reservationId,
        String idempotencyKey
    ) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        Payment existing = paymentRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            assertSameDepositRequest(existing, actor, reservationId);
            return toResponse(existing);
        }

        User initiator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Reservation reservation = reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        validateReservationCanBePaid(reservation);

        Payment latestPayment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
                reservationId, PaymentType.RESERVATION_DEPOSIT
            )
            .orElse(null);
        if (latestPayment != null && (
            latestPayment.getStatus() == PaymentStatus.PENDING
                || latestPayment.getStatus() == PaymentStatus.PROCESSING
                || latestPayment.getStatus() == PaymentStatus.PAID
        )) {
            return toResponse(latestPayment);
        }

        ReservationPricingSnapshot snapshot = snapshotRepository
            .findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        Payment payment = new Payment();
        payment.setInitiatedBy(initiator);
        payment.setReservation(reservation);
        payment.setAmount(snapshot.getReservationDepositAmount());
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setIdempotencyKey(key);
        payment.setProvider("MOMO");
        payment.setGatewayIntentId("pi_" + UUID.randomUUID());
        Payment saved = paymentRepository.saveAndFlush(payment);
        PaymentIntentResponse response = toResponse(saved);
        auditLogService.recordMutation(initiator, "PAYMENT_INTENT_CREATED", "Payment", saved.getId(), null, null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse getReservationDeposit(
        ActorPrincipal actor,
        UUID reservationId
    ) {
        reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        Payment payment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
                reservationId, PaymentType.RESERVATION_DEPOSIT
            )
            .orElseThrow(() -> ApiExceptions.notFound("Reservation payment was not found"));
        return toResponse(payment);
    }

    @Transactional
    public PaymentIntentResponse handleWebhook(String provider, String signature, String rawBody) {
        if (!"MOMO".equalsIgnoreCase(provider)) {
            throw ApiExceptions.validation("Payment provider is not supported", null);
        }
        String normalizedProvider = provider.trim().toUpperCase(java.util.Locale.ROOT);
        verifySignature(signature, rawBody);
        PaymentWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, PaymentWebhookPayload.class);
        } catch (JsonProcessingException exception) {
            throw ApiExceptions.validation("Payment webhook payload is malformed", null);
        }
        if (payload.eventId() == null || payload.eventId().isBlank() || payload.paymentId() == null || payload.status() == null) {
            throw ApiExceptions.validation("Payment webhook is missing required fields", null);
        }
        if (payload.status() != PaymentStatus.PAID
            && payload.status() != PaymentStatus.FAILED
            && payload.status() != PaymentStatus.CANCELLED) {
            throw ApiExceptions.validation("Payment webhook status is not supported", null);
        }

        PaymentWebhookEvent duplicate = paymentWebhookEventRepository
            .findByProviderAndProviderEventId(normalizedProvider, payload.eventId())
            .orElse(null);
        if (duplicate != null) {
            return toResponse(duplicate.getPayment());
        }

        Payment payment = paymentRepository.findById(payload.paymentId())
            .orElseThrow(() -> ApiExceptions.notFound("Payment intent was not found"));
        if (payload.amount() != null && payment.getAmount().compareTo(payload.amount()) != 0) {
            throw ApiExceptions.conflict("Payment webhook amount does not match the intent");
        }
        PaymentStatus before = payment.getStatus();
        payment.setProvider(normalizedProvider);
        payment.setStatus(payload.status());
        if (payload.status() == PaymentStatus.PAID) {
            payment.setProviderPaidAt(Instant.now());
            confirmReservationOrRequireReconciliation(payment);
        }
        Payment saved = paymentRepository.saveAndFlush(payment);

        PaymentWebhookEvent webhookEvent = new PaymentWebhookEvent();
        webhookEvent.setPayment(saved);
        webhookEvent.setProvider(normalizedProvider);
        webhookEvent.setProviderEventId(payload.eventId());
        webhookEvent.setPayload(rawBody);
        webhookEvent.setProcessedAt(Instant.now());
        paymentWebhookEventRepository.saveAndFlush(webhookEvent);
        PaymentIntentResponse response = toResponse(saved);
        auditLogService.recordMutation(null, "PAYMENT_WEBHOOK_APPLIED", "Payment", saved.getId(), null,
            new PaymentState(before), new PaymentState(saved.getStatus()));
        return response;
    }

    private void assertSameDepositRequest(Payment existing, ActorPrincipal actor, UUID reservationId) {
        if (!existing.getInitiatedBy().getId().equals(actor.userId())) {
            throw ApiExceptions.conflict("Idempotency-Key is already used by another actor");
        }
        if (!existing.getReservation().getId().equals(reservationId)
            || !"VND".equals(existing.getCurrency())
            || existing.getPurpose() != PaymentType.RESERVATION_DEPOSIT) {
            throw ApiExceptions.conflict("Idempotency-Key is already used for a different request");
        }
    }

    private void validateReservationCanBePaid(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) {
            throw ApiExceptions.conflict("Reservation is not awaiting payment");
        }
        if (reservation.getHoldExpiresAt() == null
            || !reservation.getHoldExpiresAt().isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Reservation hold has expired");
        }
    }

    private void confirmReservationOrRequireReconciliation(Payment payment) {
        Reservation reservation = payment.getReservation();
        boolean confirmable = reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT
            && reservation.getHoldExpiresAt() != null
            && reservation.getHoldExpiresAt().isAfter(Instant.now());
        if (!confirmable) {
            payment.setStatus(PaymentStatus.RECONCILIATION_REQUIRED);
            return;
        }
        Instant now = Instant.now();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setDepositPaidAt(now);
        reservation.setConfirmedAt(now);
        reservationRepository.saveAndFlush(reservation);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw ApiExceptions.validation("Idempotency-Key is required and must be at most 100 characters", null);
        }
        return value.trim();
    }

    private void verifySignature(String signature, String rawBody) {
        if (signature == null || signature.isBlank() || paymentProperties.getWebhookSecret() == null
            || paymentProperties.getWebhookSecret().isBlank()) {
            throw ApiExceptions.unauthorized("Payment webhook signature is invalid");
        }
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                paymentProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] actual = java.util.HexFormat.of().parseHex(signature.trim());
            if (!MessageDigest.isEqual(expected, actual)) {
                throw ApiExceptions.unauthorized("Payment webhook signature is invalid");
            }
        } catch (IllegalArgumentException | java.security.GeneralSecurityException exception) {
            throw ApiExceptions.unauthorized("Payment webhook signature is invalid");
        }
    }

    private PaymentIntentResponse toResponse(Payment payment) {
        return new PaymentIntentResponse(
            payment.getId(),
            payment.getReservation().getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getPurpose(),
            payment.getStatus(),
            payment.getProvider(),
            payment.getGatewayIntentId(),
            payment.getCreatedAt()
        );
    }

    private record PaymentState(PaymentStatus status) {
    }
}
