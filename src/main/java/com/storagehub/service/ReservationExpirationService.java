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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationExpirationService {

    private static final List<ReservationStatus> EXPIRABLE_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW,
        ReservationStatus.AWAITING_PAYMENT
    );

    private static final List<PaymentStatus> EXPIRABLE_PAYMENT_STATUSES = List.of(
        PaymentStatus.PENDING,
        PaymentStatus.PROCESSING
    );

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional
    public int expireDueReservations() {
        Instant now = Instant.now();
        List<Reservation> reservations = reservationRepository
            .findTop100ByStatusInAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                EXPIRABLE_STATUSES, now
            );
        for (Reservation reservation : reservations) {
            expireReservation(reservation, now);
        }
        return reservations.size();
    }

    private void expireReservation(Reservation reservation, Instant now) {
        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.EXPIRED);
        reservation.setExpiredAt(now);
        reservationRepository.saveAndFlush(reservation);

        List<Payment> payments = paymentRepository.findAllByReservation_IdAndStatusIn(
            reservation.getId(), EXPIRABLE_PAYMENT_STATUSES
        );
        for (Payment payment : payments) {
            payment.setStatus(PaymentStatus.EXPIRED);
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
    }
}
