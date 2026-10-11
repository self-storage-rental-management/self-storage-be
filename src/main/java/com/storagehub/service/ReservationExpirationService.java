package com.storagehub.service;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationExpirationService {

    private static final List<ReservationStatus> EXPIRABLE_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW
    );

    private static final List<PaymentStatus> EXPIRABLE_PAYMENT_STATUSES = List.of(
        PaymentStatus.PENDING,
        PaymentStatus.PROCESSING
    );

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final VnpayService vnpayService;

    @Value("${app.reservation.complaint-window-minutes:30}")
    private long complaintWindowMinutes = 30;

    @Transactional
    public int expireDueReservations() {
        Instant now = Instant.now();
        List<Reservation> reservations = reservationRepository
            .findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                EXPIRABLE_STATUSES, now
            );
        int transitionCount = 0;
        for (Reservation reservation : reservations) {
            if (expireReservation(reservation, now)) {
                transitionCount++;
            }
        }
        List<Reservation> paymentDue = reservationRepository
            .findTop100ByStatusAndPaymentExpiresAtLessThanEqualOrderByPaymentExpiresAtAsc(
                ReservationStatus.AWAITING_PAYMENT, now
            );
        for (Reservation reservation : paymentDue) {
            if (moveToGraceOrExpire(reservation, now)) {
                transitionCount++;
            }
        }
        List<Reservation> graceDue = reservationRepository
            .findTop100ByStatusAndComplaintExpiresAtLessThanEqualOrderByComplaintExpiresAtAsc(
                ReservationStatus.PAYMENT_GRACE, now
            );
        for (Reservation reservation : graceDue) {
            if (expireReservation(reservation, now)) {
                transitionCount++;
            }
        }
        return transitionCount;
    }

    private boolean moveToGraceOrExpire(Reservation reservation, Instant now) {
        List<Payment> unresolvedPayments = paymentRepository.findAllByReservation_IdAndStatusIn(
            reservation.getId(), EXPIRABLE_PAYMENT_STATUSES
        );
        if (unresolvedPayments.isEmpty()) {
            return expireReservation(reservation, now);
        }

        boolean hasUnknownResult = false;
        for (Payment payment : unresolvedPayments) {
            VnpayService.ReconciliationOutcome outcome = vnpayService.reconcileAtPaymentDeadline(payment);
            if (outcome == VnpayService.ReconciliationOutcome.SUCCESS) {
                paymentRepository.saveAndFlush(payment);
                return true;
            }
            if (outcome == VnpayService.ReconciliationOutcome.UNKNOWN) {
                hasUnknownResult = true;
            }
        }
        if (!hasUnknownResult) {
            paymentRepository.saveAll(unresolvedPayments);
            return expireReservation(reservation, now);
        }

        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.PAYMENT_GRACE);
        reservation.setComplaintExpiresAt(now.plus(java.time.Duration.ofMinutes(complaintWindowMinutes)));
        reservationRepository.saveAndFlush(reservation);

        for (Payment payment : unresolvedPayments) {
            if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.PROCESSING) continue;
            payment.setStatus(PaymentStatus.NOT_RECEIVED);
            payment.setProcessedAt(now);
            payment.setFailureCode("VNPAY_RESULT_NOT_RECEIVED");
            payment.setFailureReason("The reservation deadline passed before VNPAY returned a final result");
        }
        paymentRepository.saveAll(unresolvedPayments);

        auditLogService.recordMutation(
            null, "RESERVATION_PAYMENT_GRACE_STARTED", "Reservation", reservation.getId(),
            reservation.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", ReservationStatus.PAYMENT_GRACE,
                "complaintExpiresAt", reservation.getComplaintExpiresAt())
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.PAYMENT,
            "Chưa nhận được kết quả thanh toán",
            "Đơn " + reservation.getReservationCode()
                + " đang chờ đối soát VNPay. Nếu tài khoản đã bị trừ tiền, bạn có "
                + complaintWindowMinutes + " phút để gửi khiếu nại kèm bằng chứng.",
            reservation.getId()
        );
        return true;
    }

    private boolean expireReservation(Reservation reservation, Instant now) {
        List<Payment> payments = paymentRepository.findAllByReservation_IdAndStatusIn(
            reservation.getId(), EXPIRABLE_PAYMENT_STATUSES
        );
        boolean paymentIsProcessing = payments.stream()
            .anyMatch(payment -> payment.getStatus() == PaymentStatus.PROCESSING);
        if (paymentIsProcessing) {
            return false;
        }

        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.EXPIRED);
        reservation.setExpiredAt(now);
        reservation.setArchivedAt(now);
        reservationRepository.saveAndFlush(reservation);

        for (Payment payment : payments) {
            payment.setStatus(PaymentStatus.CANCELLED);
        }
        if (!payments.isEmpty()) {
            paymentRepository.saveAll(payments);
        }

        auditLogService.recordMutation(
            null, "RESERVATION_EXPIRED", "Reservation", reservation.getId(),
            reservation.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", ReservationStatus.EXPIRED, "expiredAt", now)
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.RESERVATION,
            "Đơn đặt kho đã hết hạn",
            "Đơn " + reservation.getReservationCode()
                + " đã hết thời gian giữ chỗ. Bạn có thể tạo một đơn mới.",
            reservation.getId()
        );
        return true;
    }
}
