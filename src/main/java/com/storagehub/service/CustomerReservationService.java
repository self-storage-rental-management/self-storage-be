package com.storagehub.service;

import com.storagehub.api.reservation.CancelReservationRequest;
import com.storagehub.api.reservation.CheckInAppointmentRequest;
import com.storagehub.api.reservation.ReservationDetailResponse;
import com.storagehub.api.reservation.ReservationGoodsItemResponse;
import com.storagehub.api.reservation.ReservationResponse;
import com.storagehub.api.file.FileAssetResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReservationService {

    private final ReservationRepository reservationRepository;
    private final CheckInRepository checkInRepository;
    private final PaymentRepository paymentRepository;
    private final CheckInRepository checkInRepository;
    private final FileAssetRepository fileAssetRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Autowired
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        CheckInRepository checkInRepository,
        FileAssetRepository fileAssetRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.checkInRepository = checkInRepository;
        this.fileAssetRepository = fileAssetRepository;
        this.snapshotRepository = snapshotRepository;
        this.goodsItemRepository = goodsItemRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    /** Compatibility constructor retained for service unit tests and existing integrations. */
    public CustomerReservationService(
        ReservationRepository reservationRepository,
        PaymentRepository paymentRepository,
        ReservationPricingSnapshotRepository snapshotRepository,
        ReservationGoodsItemRepository goodsItemRepository,
        AuditLogService auditLogService,
        NotificationService notificationService
    ) {
        this(reservationRepository, paymentRepository, null, null, snapshotRepository,
            goodsItemRepository, auditLogService, notificationService);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> list(
        ActorPrincipal actor,
        ReservationStatus status,
        int page,
        int size,
        String correlationId
    ) {
        PageRequest pageable = createPageRequest(page, size);
        Page<Reservation> reservations;
        if (status == null) {
            reservations = reservationRepository.findAllByCustomer_IdAndArchivedAtIsNullAndStatusNot(
                actor.userId(), ReservationStatus.REJECTED, pageable
            );
        } else {
            reservations = reservationRepository.findAllByCustomer_IdAndStatusAndArchivedAtIsNull(
                actor.userId(), status, pageable
            );
        }
        return PageResponse.from(reservations.map(this::toSummaryResponse), correlationId);
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse getDetail(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        return toDetailResponse(reservation);
    }

    @Transactional
    public ReservationResponse setCheckInAppointment(
        ActorPrincipal actor,
        UUID reservationId,
        CheckInAppointmentRequest request
    ) {
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        if (reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Reservation is not ready to set a check-in appointment");
        }
        reservation.setAppointmentAt(request.appointmentAt());
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId).orElse(null);
        if (checkIn != null && checkIn.getStatus() == com.storagehub.domain.model.CheckInStatus.scheduled) {
            checkIn.setScheduledAt(request.appointmentAt());
            checkInRepository.saveAndFlush(checkIn);
        }
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        auditLogService.recordMutation(
            saved.getCustomer(), "CUSTOMER_CHECKIN_APPOINTMENT_SET", "Reservation", saved.getId(),
            saved.getFacility().getId(), null, Map.of("appointmentAt", saved.getAppointmentAt())
        );
        return toSummaryResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<FileAssetResponse> listCheckInDocuments(ActorPrincipal actor, UUID reservationId) {
        Reservation reservation = findOwnedReservation(actor, reservationId);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (checkIn == null) {
            return List.of();
        }
        return fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc("CHECK_IN", checkIn.getId())
            .stream()
            .filter(asset -> asset.getStatus() == FileAssetStatus.ACTIVE)
            .map(asset -> new FileAssetResponse(
                asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(),
                asset.getChecksumSha256(), asset.getEntityType(), asset.getEntityId(), asset.getStatus(), asset.getCreatedAt()
            ))
            .toList();
    }

    @Transactional
    public ReservationDetailResponse cancel(
        ActorPrincipal actor,
        UUID reservationId,
        CancelReservationRequest request
    ) {
        Reservation reservation = reservationRepository
            .findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        ReservationStatus previousStatus = reservation.getStatus();
        if (!canBeCancelled(previousStatus)) {
            throw ApiExceptions.conflict(
                "Reservation cannot be cancelled when status is " + previousStatus
            );
        }

        List<Payment> payments = paymentRepository.findReservationPaymentsForUpdate(
            reservationId, PaymentType.RESERVATION_DEPOSIT
        );
        if (payments.stream().anyMatch(this::isPaymentBeingFinalized)) {
            throw ApiExceptions.conflict(
                "Reservation cannot be cancelled while payment is processing"
            );
        }

        payments.stream()
            .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
            .forEach(payment -> payment.setStatus(PaymentStatus.CANCELLED));

        // A paid deposit is intentionally non-refundable when the customer cancels
        // before check-in. Assignment/unit release belongs to the operations flow;
        // this service does not clear assignedUnit or change StorageUnit status.
        Instant cancelledAt = Instant.now();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(cancelledAt);
        reservation.setArchivedAt(cancelledAt);
        reservation.setCancelReason(request.getReason().trim());
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        ReservationDetailResponse response = toDetailResponse(saved);

        auditLogService.recordMutation(
            saved.getCustomer(), "RESERVATION_CANCELLED", "Reservation", saved.getId(),
            saved.getFacility().getId(),
            Map.of("status", previousStatus),
            Map.of("status", saved.getStatus(), "reason", saved.getCancelReason())
        );
        notificationService.createNotification(
            saved.getCustomer().getId(), NotificationType.RESERVATION,
            "Đơn giữ kho đã được hủy",
            "Đơn " + saved.getReservationCode() + " đã được hủy theo yêu cầu của bạn.",
            saved.getId()
        );
        return response;
    }

    private Reservation findOwnedReservation(ActorPrincipal actor, UUID reservationId) {
        return reservationRepository.findByIdAndCustomer_Id(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
    }

    private boolean canBeCancelled(ReservationStatus status) {
        return status == ReservationStatus.AWAITING_EMAIL
            || status == ReservationStatus.AWAITING_REVIEW
            || status == ReservationStatus.AWAITING_PAYMENT
            || status == ReservationStatus.PAYMENT_GRACE
            || status == ReservationStatus.CONFIRMED
            || status == ReservationStatus.UNIT_RESERVED
            || status == ReservationStatus.READY_FOR_CHECKIN;
    }

    private boolean isPaymentBeingFinalized(Payment payment) {
        return payment.getStatus() == PaymentStatus.PROCESSING;
    }

    private PageRequest createPageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation(
                "page must be >= 0 and size must be between 1 and 100", null
            );
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private ReservationResponse toSummaryResponse(Reservation reservation) {
        ReservationPricingSnapshot snapshot = snapshotRepository
            .findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        return new ReservationResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getSourceQuote().getId(),
            reservation.getFacility().getId(), reservation.getUnitType().getId(), reservation.getStatus(),
            reservation.getGoodsReviewStatus(), reservation.getCompatibilityResult(),
            reservation.getStartDate(), reservation.getEndDate(), snapshot.getNetRentalAmount(),
            snapshot.getReservationDepositAmount(), snapshot.getSecurityDepositAmount(),
            snapshot.getRemainingRentalAmount(), snapshot.getDueAtCheckIn(),
            snapshot.getTotalInitialObligation(), reservation.getTotalGoodsVolumeM3(),
            reservation.getTotalGoodsWeightKg(), reservation.getHoldExpiresAt(), reservation.getCreatedAt(),
            snapshot.getPricingPackageCode(), snapshot.getRentalMonths(), snapshot.getGrossRentalAmount(),
            snapshot.getDiscountRate(), snapshot.getDiscountAmount(), reservation.getAppointmentAt()
        );
    }

    private ReservationDetailResponse toDetailResponse(Reservation reservation) {
        List<ReservationGoodsItemResponse> goodsItems = goodsItemRepository
            .findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream()
            .map(this::toGoodsItemResponse)
            .toList();

        ReservationDetailResponse response = new ReservationDetailResponse();
        response.setReservation(toSummaryResponse(reservation));
        response.setGoodsCondition(reservation.getGoodsCondition());
        response.setNotes(reservation.getNotes());
        response.setPaymentExpiresAt(reservation.getPaymentExpiresAt());
        response.setComplaintExpiresAt(reservation.getComplaintExpiresAt());
        response.setArchivedAt(reservation.getArchivedAt());
        response.setConfirmedAt(reservation.getConfirmedAt());
        response.setCancelledAt(reservation.getCancelledAt());
        response.setCancelReason(reservation.getCancelReason());
        response.setGoodsItems(goodsItems);
        return response;
    }

    private ReservationGoodsItemResponse toGoodsItemResponse(ReservationGoodsItem item) {
        ReservationGoodsItemResponse response = new ReservationGoodsItemResponse();
        response.setId(item.getId());
        response.setCategory(item.getCategory());
        response.setCustomGoodsName(item.getCustomGoodsName());
        response.setMaterialName(item.getMaterialName());
        response.setCustomMaterial(item.getCustomMaterial());
        response.setDescription(item.getDescription());
        response.setQuantity(item.getQuantity());
        response.setLengthCm(item.getLengthCm());
        response.setWidthCm(item.getWidthCm());
        response.setHeightCm(item.getHeightCm());
        response.setWeightPerItemKg(item.getWeightKg());
        response.setFragile(item.isFragile());
        response.setCustomerNote(item.getCustomerNote());
        response.setRequiresStaffReview(item.isRequiresStaffReview());
        response.setReviewStatus(item.getReviewStatus());
        response.setReviewNote(item.getReviewNote());
        return response;
    }
}
