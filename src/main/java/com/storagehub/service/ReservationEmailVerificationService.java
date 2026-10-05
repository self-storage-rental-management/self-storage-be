package com.storagehub.service;

import com.storagehub.api.reservation.ReservationEmailVerificationRequest;
import com.storagehub.api.reservation.ReservationEmailVerificationResponse;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationEmailVerification;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.repo.ReservationEmailVerificationRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationEmailVerificationService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int OTP_VALID_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int REVIEW_HOLD_HOURS = 24;
    private static final int PAYMENT_HOLD_HOURS = 24;

    private final ReservationRepository reservationRepository;
    private final ReservationEmailVerificationRepository verificationRepository;
    private final JwtService jwtService;
    private final AuthEmailService emailService;
    private final AuditLogService auditLogService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.expose-development-code:false}")
    private boolean exposeDevelopmentCode;

    @Transactional
    public ReservationEmailVerificationResponse resend(
        ActorPrincipal actor,
        UUID reservationId
    ) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        validateWaitingForEmail(reservation);
        Instant now = Instant.now();
        validateHold(reservation, now);

        ReservationEmailVerification verification = verificationRepository
            .findByReservation_Id(reservationId)
            .orElseGet(ReservationEmailVerification::new);
        if (verification.getVerifiedAt() != null) {
            throw ApiExceptions.conflict("Reservation email is already verified");
        }
        if (verification.getLastSentAt() != null
            && verification.getLastSentAt().plus(RESEND_COOLDOWN_SECONDS, ChronoUnit.SECONDS).isAfter(now)) {
            throw ApiExceptions.conflict("Please wait 60 seconds before requesting another code");
        }

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        verification.setReservation(reservation);
        verification.setOtpHash(jwtService.hash(otp));
        verification.setExpiresAt(now.plus(OTP_VALID_MINUTES, ChronoUnit.MINUTES));
        verification.setLastSentAt(now);
        verification.setFailedAttempts(0);
        verification.setResendCount(verification.getResendCount() + 1);
        verificationRepository.saveAndFlush(verification);
        emailService.sendReservationVerification(reservation, otp);

        return toResponse(verification, exposeDevelopmentCode ? otp : null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ReservationEmailVerificationResponse verify(
        ActorPrincipal actor,
        UUID reservationId,
        ReservationEmailVerificationRequest request
    ) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        validateWaitingForEmail(reservation);
        Instant now = Instant.now();
        validateHold(reservation, now);

        ReservationEmailVerification verification = verificationRepository
            .findByReservation_Id(reservationId)
            .orElseThrow(this::invalidCode);
        if (verification.getVerifiedAt() != null
            || !verification.getExpiresAt().isAfter(now)
            || verification.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw invalidCode();
        }

        String suppliedHash = jwtService.hash(request.getCode().trim());
        if (!constantTimeEquals(suppliedHash, verification.getOtpHash())) {
            verification.setFailedAttempts(verification.getFailedAttempts() + 1);
            verificationRepository.saveAndFlush(verification);
            throw invalidCode();
        }

        ReservationStatus nextStatus = reservation.getGoodsReviewStatus() == GoodsReviewStatus.PENDING
            ? ReservationStatus.AWAITING_REVIEW
            : ReservationStatus.AWAITING_PAYMENT;
        verification.setVerifiedAt(now);
        reservation.setStatus(nextStatus);
        if (nextStatus == ReservationStatus.AWAITING_REVIEW) {
            Instant reviewDueAt = now.plus(REVIEW_HOLD_HOURS, ChronoUnit.HOURS);
            reservation.setGoodsReviewSubmittedAt(now);
            reservation.setGoodsReviewDueAt(reviewDueAt);
            reservation.setHoldExpiresAt(reviewDueAt);
        } else {
            Instant paymentDueAt = now.plus(PAYMENT_HOLD_HOURS, ChronoUnit.HOURS);
            reservation.setPaymentExpiresAt(paymentDueAt);
            reservation.setHoldExpiresAt(paymentDueAt);
        }
        verificationRepository.saveAndFlush(verification);
        reservationRepository.saveAndFlush(reservation);

        auditLogService.recordMutation(
            reservation.getCustomer(), "RESERVATION_EMAIL_VERIFIED", "Reservation",
            reservation.getId(), reservation.getFacility().getId(),
            Map.of("status", ReservationStatus.AWAITING_EMAIL),
            Map.of("status", nextStatus)
        );
        return toResponse(verification, null);
    }

    private Reservation findOwnedReservation(ActorPrincipal actor, UUID reservationId) {
        return reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
    }

    private void validateWaitingForEmail(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.AWAITING_EMAIL) {
            throw ApiExceptions.conflict(
                "Reservation is not waiting for email verification"
            );
        }
    }

    private void validateHold(Reservation reservation, Instant now) {
        if (reservation.getHoldExpiresAt() == null || !reservation.getHoldExpiresAt().isAfter(now)) {
            throw ApiExceptions.conflict("Reservation hold has expired");
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ReservationEmailVerificationResponse toResponse(
        ReservationEmailVerification verification,
        String developmentCode
    ) {
        return new ReservationEmailVerificationResponse(
            verification.getReservation().getId(),
            verification.getReservation().getStatus(),
            verification.getVerifiedAt() != null,
            verification.getExpiresAt(),
            verification.getLastSentAt().plus(RESEND_COOLDOWN_SECONDS, ChronoUnit.SECONDS),
            developmentCode
        );
    }

    private com.storagehub.common.api.ApiException invalidCode() {
        return ApiExceptions.unauthorized("The reservation verification code is invalid or expired");
    }
}
