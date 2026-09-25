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
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Transactional
    public PaymentIntentResponse createIntent(ActorPrincipal actor, CreatePaymentIntentRequest request, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        Payment existing = paymentRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            assertSameRequest(existing, actor, request);
            return toResponse(existing);
        }

        User initiator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Payment payment = new Payment();
        payment.setInitiatedBy(initiator);
        payment.setReservationId(request.reservationId());
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency().trim().toUpperCase(java.util.Locale.ROOT));
        payment.setPurpose(request.purpose());
        payment.setIdempotencyKey(key);
        payment.setProvider("configured-gateway");
        payment.setGatewayIntentId("pi_" + UUID.randomUUID());
        Payment saved = paymentRepository.saveAndFlush(payment);
        PaymentIntentResponse response = toResponse(saved);
        auditLogService.recordMutation(initiator, "PAYMENT_INTENT_CREATED", "Payment", saved.getId(), null, null, response);
        return response;
    }

    @Transactional
    public PaymentIntentResponse handleWebhook(String provider, String signature, String rawBody) {
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
        if (payload.status() != PaymentStatus.paid && payload.status() != PaymentStatus.failed && payload.status() != PaymentStatus.cancelled) {
            throw ApiExceptions.validation("Payment webhook status is not supported", null);
        }

        Payment duplicate = paymentRepository.findByProviderEventId(payload.eventId()).orElse(null);
        if (duplicate != null) {
            return toResponse(duplicate);
        }

        Payment payment = paymentRepository.findById(payload.paymentId())
            .orElseThrow(() -> ApiExceptions.notFound("Payment intent was not found"));
        if (payload.amount() != null && payment.getAmount().compareTo(payload.amount()) != 0) {
            throw ApiExceptions.conflict("Payment webhook amount does not match the intent");
        }
        PaymentStatus before = payment.getStatus();
        payment.setProvider(provider);
        payment.setProviderEventId(payload.eventId());
        payment.setStatus(payload.status());
        Payment saved = paymentRepository.saveAndFlush(payment);
        PaymentIntentResponse response = toResponse(saved);
        auditLogService.recordMutation(null, "PAYMENT_WEBHOOK_APPLIED", "Payment", saved.getId(), null,
            new PaymentState(before), new PaymentState(saved.getStatus()));
        return response;
    }

    private void assertSameRequest(Payment existing, ActorPrincipal actor, CreatePaymentIntentRequest request) {
        if (!existing.getInitiatedBy().getId().equals(actor.userId())) {
            throw ApiExceptions.conflict("Idempotency-Key is already used by another actor");
        }
        String currency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (existing.getReservationId() != null && !existing.getReservationId().equals(request.reservationId())
            || existing.getReservationId() == null && request.reservationId() != null
            || existing.getAmount().compareTo(request.amount()) != 0
            || !existing.getCurrency().equals(currency)
            || existing.getPurpose() != request.purpose()) {
            throw ApiExceptions.conflict("Idempotency-Key is already used for a different request");
        }
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
            payment.getReservationId(),
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
