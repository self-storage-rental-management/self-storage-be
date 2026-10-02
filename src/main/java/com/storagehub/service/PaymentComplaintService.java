package com.storagehub.service;

import com.storagehub.api.payment.CreatePaymentComplaintRequest;
import com.storagehub.api.payment.PaymentComplaintDecisionRequest;
import com.storagehub.api.payment.PaymentComplaintResponse;
import com.storagehub.api.payment.ManagerPaymentQueueItemResponse;
import com.storagehub.api.reservation.ReservationGoodsItemResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentComplaintService {
    private static final String FILE_ENTITY_TYPE = "PAYMENT_COMPLAINT";
    private static final long MAX_COMPLAINTS_PER_DAY = 5;
    private static final List<PaymentComplaintStatus> ACTIVE_STATUSES = List.of(
        PaymentComplaintStatus.REVIEW_OVERDUE, PaymentComplaintStatus.PENDING
    );

    private final PaymentComplaintRepository complaintRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final FileAssetRepository fileAssetRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final ReservationPricingSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional
    public PaymentComplaintResponse submit(
        ActorPrincipal actor, UUID reservationId, CreatePaymentComplaintRequest request
    ) {
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(reservationId, actor.userId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        Instant now = Instant.now();
        if (reservation.getStatus() != ReservationStatus.PAYMENT_GRACE
            || reservation.getComplaintExpiresAt() == null
            || !reservation.getComplaintExpiresAt().isAfter(now)) {
            throw ApiExceptions.conflict("Reservation is not in the active payment complaint window");
        }
        if (complaintRepository.findByReservation_Id(reservationId).isPresent()) {
            throw ApiExceptions.conflict("A payment complaint already exists for this reservation");
        }
        if (complaintRepository.countByCustomer_IdAndSubmittedAtAfter(
            actor.userId(), now.minus(24, ChronoUnit.HOURS)
        ) >= MAX_COMPLAINTS_PER_DAY) {
            throw ApiExceptions.conflict("Payment complaint limit reached; please try again later");
        }
        Payment payment = paymentRepository.findReservationPaymentsForUpdate(
            reservationId, PaymentType.RESERVATION_DEPOSIT
        ).stream().findFirst().orElseThrow(() -> ApiExceptions.conflict("Reservation payment is missing"));
        List<FileAsset> images = requireOwnedImages(actor, request.imageIds());

        PaymentComplaint complaint = new PaymentComplaint();
        complaint.setReservation(reservation);
        complaint.setPayment(payment);
        complaint.setCustomer(reservation.getCustomer());
        complaint.setReason(request.reason().trim());
        complaint.setSubmittedAt(now);
        complaint.setReviewDueAt(now.plus(24, ChronoUnit.HOURS));
        PaymentComplaint saved = complaintRepository.saveAndFlush(complaint);
        for (FileAsset image : images) {
            image.setEntityType(FILE_ENTITY_TYPE);
            image.setEntityId(saved.getId());
        }
        fileAssetRepository.saveAll(images);
        reservation.setStatus(ReservationStatus.PAYMENT_REVIEW);
        reservationRepository.saveAndFlush(reservation);
        auditLogService.recordMutation(
            reservation.getCustomer(), "PAYMENT_COMPLAINT_SUBMITTED", "PaymentComplaint",
            saved.getId(), reservation.getFacility().getId(), null,
            Map.of("status", saved.getStatus(), "reviewDueAt", saved.getReviewDueAt())
        );
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PaymentComplaintResponse getForCustomer(ActorPrincipal actor, UUID reservationId) {
        return complaintRepository.findByReservation_IdAndCustomer_Id(reservationId, actor.userId())
            .map(this::toResponse)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
    }

    @Transactional
    public PaymentComplaintResponse withdraw(ActorPrincipal actor, UUID complaintId) {
        PaymentComplaint preview = complaintRepository.findById(complaintId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
        if (!preview.getCustomer().getId().equals(actor.userId())) {
            throw ApiExceptions.notFound("Payment complaint was not found");
        }
        Reservation reservation = reservationRepository.findOwnedByIdForUpdate(
            preview.getReservation().getId(), actor.userId()
        ).orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        PaymentComplaint complaint = complaintRepository.findByIdForUpdate(complaintId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
        if ((complaint.getStatus() != PaymentComplaintStatus.PENDING
            && complaint.getStatus() != PaymentComplaintStatus.REVIEW_OVERDUE)
            || reservation.getStatus() != ReservationStatus.PAYMENT_REVIEW) {
            throw ApiExceptions.conflict("Payment complaint can no longer be withdrawn");
        }
        Instant now = Instant.now();
        complaint.setStatus(PaymentComplaintStatus.WITHDRAWN);
        complaint.setWithdrawnAt(now);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(now);
        reservation.setCancelReason("Customer withdrew the payment complaint");
        reservation.setArchivedAt(now);
        reservationRepository.saveAndFlush(reservation);
        PaymentComplaint saved = complaintRepository.saveAndFlush(complaint);
        auditLogService.recordMutation(
            reservation.getCustomer(), "PAYMENT_COMPLAINT_WITHDRAWN", "PaymentComplaint",
            saved.getId(), reservation.getFacility().getId(), null,
            Map.of("status", saved.getStatus(), "reservationStatus", reservation.getStatus())
        );
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentComplaintResponse> managerQueue(
        ActorPrincipal actor, UUID facilityId, int page, int size, String correlationId
    ) {
        requireManager(actor, SystemPermission.VIEW_PAYMENTS);
        if (facilityId != null) facilityScopeService.assertCanRead(actor, facilityId);
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0)) : actor.facilityScopes().keySet().stream().toList();
        Page<PaymentComplaintResponse> result = complaintRepository.findManagerQueue(
            ACTIVE_STATUSES, facilityId, scoped, facilityIds, pageRequest(page, size)
        ).map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ManagerPaymentQueueItemResponse> managerReviewQueue(
        ActorPrincipal actor, UUID facilityId, int page, int size, String correlationId
    ) {
        requireManager(actor, SystemPermission.VIEW_PAYMENTS);
        if (facilityId != null) facilityScopeService.assertCanRead(actor, facilityId);
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0)) : actor.facilityScopes().keySet().stream().toList();
        Page<ManagerPaymentQueueItemResponse> result = complaintRepository
            .findManagerPaymentQueue(facilityId, scoped, facilityIds, pageRequest(page, size))
            .map(row -> toQueueItem((Reservation) row[0], (PaymentComplaint) row[1]));
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public PaymentComplaintResponse managerDetail(ActorPrincipal actor, UUID complaintId) {
        requireManager(actor, SystemPermission.VIEW_PAYMENTS);
        PaymentComplaint complaint = complaintRepository.findById(complaintId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
        facilityScopeService.assertCanRead(actor, complaint.getReservation().getFacility().getId());
        return toResponse(complaint);
    }

    @Transactional
    public PaymentComplaintResponse decide(
        ActorPrincipal actor, UUID complaintId, PaymentComplaintDecisionRequest request
    ) {
        requireManager(actor, SystemPermission.MANAGE_PAYMENTS);
        PaymentComplaint preview = complaintRepository.findById(complaintId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
        facilityScopeService.assertCanOperate(actor, preview.getReservation().getFacility().getId());
        Reservation reservation = reservationRepository.findByIdForUpdate(preview.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        PaymentComplaint complaint = complaintRepository.findByIdForUpdate(complaintId)
            .orElseThrow(() -> ApiExceptions.notFound("Payment complaint was not found"));
        if (!ACTIVE_STATUSES.contains(complaint.getStatus())
            || reservation.getStatus() != ReservationStatus.PAYMENT_REVIEW) {
            throw ApiExceptions.conflict("Payment complaint has already been resolved");
        }
        String reason = clean(request.reason());
        if (request.decision() == PaymentComplaintDecisionRequest.Decision.REJECT && reason == null) {
            throw ApiExceptions.validation("reason is required when rejecting a complaint", null);
        }
        User reviewer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Payment payment = paymentRepository.findReservationPaymentsForUpdate(
            reservation.getId(), PaymentType.RESERVATION_DEPOSIT
        ).stream().findFirst().orElseThrow(() -> ApiExceptions.conflict("Reservation payment is missing"));
        Instant now = Instant.now();
        complaint.setReviewedBy(reviewer);
        complaint.setReviewedAt(now);
        complaint.setDecisionReason(reason);
        if (request.decision() == PaymentComplaintDecisionRequest.Decision.APPROVE) {
            complaint.setStatus(PaymentComplaintStatus.APPROVED);
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(now);
            payment.setProcessedAt(now);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setDepositPaidAt(now);
            reservation.setConfirmedAt(now);
            notifyDecision(reservation, "Khiếu nại đã được chấp nhận",
                "Thanh toán của đơn " + reservation.getReservationCode() + " đã được ghi nhận.");
        } else {
            complaint.setStatus(PaymentComplaintStatus.REJECTED);
            payment.setStatus(PaymentStatus.NOT_RECEIVED);
            reservation.setStatus(ReservationStatus.REJECTED);
            reservation.setRejectedAt(now);
            reservation.setRejectionReason(reason);
            reservation.setArchivedAt(now);
            notifyDecision(reservation, "Khiếu nại bị từ chối", reason);
        }
        paymentRepository.saveAndFlush(payment);
        reservationRepository.saveAndFlush(reservation);
        PaymentComplaint saved = complaintRepository.saveAndFlush(complaint);
        auditLogService.recordMutation(
            reviewer, "PAYMENT_COMPLAINT_" + request.decision(), "PaymentComplaint",
            saved.getId(), reservation.getFacility().getId(), null,
            Map.of("status", saved.getStatus(), "reservationStatus", reservation.getStatus())
        );
        return toResponse(saved);
    }

    private List<FileAsset> requireOwnedImages(ActorPrincipal actor, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
            throw ApiExceptions.validation("At least one unique complaint image is required", null);
        }
        List<FileAsset> files = fileAssetRepository.findAllById(ids);
        if (files.size() != ids.size()) throw ApiExceptions.validation("A complaint image was not found", null);
        for (FileAsset file : files) {
            if (!file.getUploadedBy().getId().equals(actor.userId())
                || !file.getContentType().startsWith("image/")
                || file.getEntityId() != null) {
                throw ApiExceptions.forbidden("Complaint images must be unlinked images uploaded by the customer");
            }
        }
        return files;
    }

    private void requireManager(ActorPrincipal actor, SystemPermission permission) {
        if (!actor.hasAnyRole(RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only managers can process payment complaints");
        }
        authorizationService.require(actor, permission);
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }
        return PageRequest.of(page, size);
    }

    private void notifyDecision(Reservation reservation, String title, String message) {
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.PAYMENT,
            title, message, reservation.getId()
        );
    }

    private PaymentComplaintResponse toResponse(PaymentComplaint complaint) {
        Reservation reservation = complaint.getReservation();
        var snapshot = snapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        return new PaymentComplaintResponse(
            complaint.getId(), reservation.getId(), reservation.getReservationCode(),
            complaint.getCustomer().getId(), reservation.getFacility().getId(),
            reservation.getUnitType().getId(), reservation.getStartDate(), reservation.getEndDate(),
            complaint.getPayment().getAmount(), snapshot == null ? null : snapshot.getNetRentalAmount(),
            goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
                .stream().map(this::toGoodsItemResponse).toList(),
            complaint.getStatus(), complaint.getPayment().getStatus(),
            reservation.getStatus(), complaint.getReason(),
            fileAssetRepository.findAllByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                FILE_ENTITY_TYPE, complaint.getId()).stream().map(FileAsset::getId).toList(),
            complaint.getSubmittedAt(), complaint.getReviewDueAt(), complaint.getReviewedAt(),
            complaint.getWithdrawnAt(), complaint.getDecisionReason()
        );
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
        return response;
    }

    private ManagerPaymentQueueItemResponse toQueueItem(
        Reservation reservation, PaymentComplaint complaint
    ) {
        var snapshot = snapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        PaymentComplaintStatus complaintStatus = complaint == null ? null : complaint.getStatus();
        int priority = complaintStatus == PaymentComplaintStatus.REVIEW_OVERDUE ? 0
            : complaintStatus == PaymentComplaintStatus.PENDING ? 1
            : reservation.getStatus() == ReservationStatus.PAYMENT_GRACE ? 2 : 3;
        return new ManagerPaymentQueueItemResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getCustomer().getId(),
            reservation.getCustomer().getEmail(), reservation.getFacility().getId(),
            reservation.getUnitType().getId(), reservation.getStartDate(), reservation.getEndDate(),
            reservation.getStatus(), snapshot == null ? null : snapshot.getReservationDepositAmount(),
            complaint == null ? null : complaint.getId(), complaintStatus,
            complaint == null ? null : complaint.getReason(), reservation.getComplaintExpiresAt(),
            complaint == null ? null : complaint.getReviewDueAt(), priority, reservation.getCreatedAt()
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
