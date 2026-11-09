package com.storagehub.service;

import com.storagehub.api.payment.SimulatedPaymentResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.config.PaymentProperties;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentSimulationOutcome;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.NotificationType;
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
    private final NotificationService notificationService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.storagehub.service.email.TransactionalEmailService transactionalEmailService;

    @Value("${app.reservation.complaint-window-minutes:1440}")
    private long complaintWindowMinutes = 1440;

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
            messageFor(outcome), response.processedAt(), response.invoiceNumber(), response.invoiceStatus(),
            response.invoiceIssuedAt()
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
        if (snapshot.getTermsAcceptedAt() == null || snapshot.getTermsAcceptanceHash() == null
            || snapshot.getTermsSnapshotJson() == null || snapshot.getTermsSnapshotJson().isBlank()) {
            throw ApiExceptions.conflict("Customer terms acceptance is required before payment");
        }
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
            ReservationPaymentTerms.apply(reservation, snapshot.getRentalMonths(), now);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setDepositPaidAt(now);
            reservation.setConfirmedAt(now);
            reservationRepository.saveAndFlush(reservation);
            notificationService.createFacilityOperationsNotifications(
                reservation.getFacility().getId(),
                NotificationType.RESERVATION,
                "Có đơn đã xác nhận chờ phân kho",
                "Đơn " + reservation.getReservationCode()
                    + " đã thanh toán cọc và đang chờ nhân viên phân kho.",
                reservation.getId()
            );
            sendSimulatedReservationPaymentBillEmail(payment, reservation, now);
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
            response.processedAt(), response.invoiceNumber(), response.invoiceStatus(), response.invoiceIssuedAt()
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
            || payment.getReservation()==null || !payment.getReservation().getId().equals(reservationId)) {
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

    private String formatVnd(java.math.BigDecimal amount) {
        if (amount == null) return "0 đ";
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("vi", "VN"));
        return nf.format(amount) + " đ";
    }

    private void sendSimulatedReservationPaymentBillEmail(Payment payment, Reservation reservation, Instant paidAt) {
        if (transactionalEmailService == null || reservation == null) return;
        try {
            var customer = reservation.getCustomer();
            String email = customer != null ? customer.getEmail() : null;
            if (email == null || email.isBlank()) return;

            String fullName = customer != null && customer.getFullName() != null ? customer.getFullName() : "Quý khách";

            String invoiceNumber = payment.getInvoiceNumber() != null ? payment.getInvoiceNumber() : reservation.getReservationCode();
            String transactionNo = payment.getGatewayTransactionNo() != null ? payment.getGatewayTransactionNo() : payment.getGatewayIntentId();
            String amountFormatted = formatVnd(payment.getAmount());
            String paidAtFormatted = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(paidAt != null ? paidAt : Instant.now());

            String unitInfo = reservation.getUnitType() != null ? reservation.getUnitType().getName() : "Gian kho lưu trữ";
            if (reservation.getAssignedUnit() != null) {
                unitInfo += " (" + reservation.getAssignedUnit().getCode() + ")";
            }

            String facilityName = reservation.getFacility() != null ? reservation.getFacility().getName() : "StorageHub";
            String facilityAddress = reservation.getFacility() != null && reservation.getFacility().getAddress() != null
                ? reservation.getFacility().getAddress() : "";

            String periodOrAppointment = "";
            if (reservation.getAppointmentAt() != null) {
                periodOrAppointment = "Lịch nhận kho: " + java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                    .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(reservation.getAppointmentAt());
            } else if (reservation.getStartDate() != null && reservation.getEndDate() != null) {
                periodOrAppointment = "Kỳ thuê: " + reservation.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + " đến " + reservation.getEndDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            }

            String note = "Biên lai này là bằng chứng xác nhận Quý khách đã thanh toán cọc giữ kho thành công cho đơn "
                + reservation.getReservationCode() + ". Quý khách vui lòng lưu lại email này để làm căn cứ đối soát hoặc xuất trình khi nhận kho tại cơ sở.";

            UUID userId = customer != null ? customer.getId() : null;
            transactionalEmailService.sendPaymentBillAsync(
                userId,
                email,
                fullName,
                invoiceNumber,
                transactionNo,
                amountFormatted,
                paidAtFormatted,
                unitInfo,
                facilityName,
                facilityAddress,
                periodOrAppointment,
                "Thanh toán cọc giữ chỗ theo báo giá",
                note
            );
        } catch (Exception ex) {
            // Ignored in simulation
        }
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
            processedAt == null ? payment.getCreatedAt() : processedAt,
            payment.getInvoiceNumber(), payment.getInvoiceStatus(), payment.getInvoiceIssuedAt()
        );
    }
}
