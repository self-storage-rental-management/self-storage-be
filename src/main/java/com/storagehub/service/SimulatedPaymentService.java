package com.storagehub.service;

import com.storagehub.api.payment.SimulatedPaymentResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.PaymentProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentSimulationOutcome;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulatedPaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final PaymentProperties paymentProperties;
    private final AuditLogService auditLogService;

    @Value("${app.reservation.complaint-window-minutes:30}")
    private long complaintWindowMinutes = 30;

    @Transactional(readOnly = true)
    public SimulatedPaymentResponse getPayment(ActorPrincipal actor, UUID reservationId) {
        reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        Payment payment = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
                reservationId, PaymentType.RESERVATION_DEPOSIT
            )
            .orElseThrow(() -> ApiExceptions.notFound("Reservation payment was not found"));
        PaymentSimulationOutcome outcome = outcomeOf(payment.getStatus());
        SimulatedPaymentResponse response = toResponse(
            payment, outcome, payment.getProcessedAt()
        );
        return new SimulatedPaymentResponse(
            response.paymentId(), response.reservationId(), response.amount(), response.currency(),
            response.outcome(), response.paymentStatus(), response.reservationStatus(),
            messageFor(outcome), response.processedAt()
        );
    }

    @Transactional
    public SimulatedPaymentResponse pay(
        ActorPrincipal actor,
        UUID reservationId,
        String idempotencyKey
    ) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        Payment existing = paymentRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            requireSameRequest(existing, actor, reservationId);
            return toResponse(existing, outcomeOf(existing.getStatus()), existing.getProcessedAt());
        }

        Reservation reservation = reservationRepository
            .findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        requirePayable(reservation);

        Payment paid = paymentRepository
            .findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
                reservationId, PaymentType.RESERVATION_DEPOSIT
            )
            .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
            .orElse(null);
        if (paid != null) {
            return toResponse(paid, PaymentSimulationOutcome.SUCCESS, paid.getProcessedAt());
        }

        ReservationPricingSnapshot snapshot = snapshotRepository.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        User initiator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Instant now = Instant.now();
        PaymentSimulationOutcome outcome = paymentProperties.getSimulationOutcome();

        Payment payment = new Payment();
        payment.setInitiatedBy(initiator);
        payment.setReservation(reservation);
        payment.setAmount(snapshot.getReservationDepositAmount());
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setGatewayIntentId("SIMULATED-" + UUID.randomUUID());
        payment.setIdempotencyKey(key);
        payment.setProcessedAt(now);

        String message;
        if (outcome == PaymentSimulationOutcome.SUCCESS) {
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(now);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setDepositPaidAt(now);
            reservation.setConfirmedAt(now);
            reservationRepository.saveAndFlush(reservation);
            message = "Payment was recorded successfully and the reservation is confirmed";
        } else if (outcome == PaymentSimulationOutcome.NOT_RECEIVED) {
            payment.setStatus(PaymentStatus.NOT_RECEIVED);
            payment.setFailureCode("NOT_RECEIVED");
            payment.setFailureReason("The simulated payment was not recorded by the system");
            reservation.setStatus(ReservationStatus.PAYMENT_GRACE);
            reservation.setComplaintExpiresAt(now.plus(java.time.Duration.ofMinutes(complaintWindowMinutes)));
            reservationRepository.saveAndFlush(reservation);
            message = "Payment has not been recorded; keep the receipt if money was deducted";
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode("SIMULATED_FAILURE");
            payment.setFailureReason("The simulated payment failed");
            message = "Payment failed; retry before the payment deadline";
        }

        Payment saved = paymentRepository.saveAndFlush(payment);
        SimulatedPaymentResponse response = toResponse(saved, outcome, now);
        auditLogService.recordMutation(
            initiator, "SIMULATED_PAYMENT_PROCESSED", "Payment", saved.getId(),
            reservation.getFacility().getId(), null,
            Map.of("outcome", outcome, "paymentStatus", saved.getStatus(),
                "reservationStatus", reservation.getStatus())
        );
        return new SimulatedPaymentResponse(
            response.paymentId(), response.reservationId(), response.amount(), response.currency(),
            response.outcome(), response.paymentStatus(), response.reservationStatus(), message,
            response.processedAt()
        );
    }

    private void requirePayable(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.AWAITING_PAYMENT) {
            throw ApiExceptions.conflict("Reservation is not awaiting payment");
        }
        Instant deadline = reservation.getPaymentExpiresAt() != null
            ? reservation.getPaymentExpiresAt() : reservation.getHoldExpiresAt();
        if (deadline == null || !deadline.isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Reservation payment deadline has expired");
        }
    }

    private void requireSameRequest(Payment payment, ActorPrincipal actor, UUID reservationId) {
        if (!payment.getInitiatedBy().getId().equals(actor.userId())
            || !payment.getReservation().getId().equals(reservationId)) {
            throw ApiExceptions.conflict("Idempotency-Key is already used for a different request");
        }
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw ApiExceptions.validation(
                "Idempotency-Key is required and must be at most 100 characters", null
            );
        }
        return value.trim();
    }

    private PaymentSimulationOutcome outcomeOf(PaymentStatus status) {
        if (status == PaymentStatus.PAID) return PaymentSimulationOutcome.SUCCESS;
        if (status == PaymentStatus.NOT_RECEIVED) return PaymentSimulationOutcome.NOT_RECEIVED;
        return PaymentSimulationOutcome.FAILED;
    }

    private String messageFor(PaymentSimulationOutcome outcome) {
        if (outcome == PaymentSimulationOutcome.SUCCESS) {
            return "Payment was recorded successfully and the reservation is confirmed";
        }
        if (outcome == PaymentSimulationOutcome.NOT_RECEIVED) {
            return "Payment has not been recorded; keep the receipt if money was deducted";
        }
        return "Payment failed; retry before the payment deadline";
    }

    private SimulatedPaymentResponse toResponse(
        Payment payment,
        PaymentSimulationOutcome outcome,
        Instant processedAt
    ) {
        return new SimulatedPaymentResponse(
            payment.getId(), payment.getReservation().getId(), payment.getAmount(),
            payment.getCurrency(), outcome, payment.getStatus(),
            payment.getReservation().getStatus(), null,
            processedAt == null ? payment.getCreatedAt() : processedAt
        );
    }
}
