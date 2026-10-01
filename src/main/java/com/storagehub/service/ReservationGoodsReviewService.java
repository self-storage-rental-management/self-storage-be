package com.storagehub.service;

import com.storagehub.api.reservation.ReservationGoodsItemResponse;
import com.storagehub.api.reservation.ReservationReviewDecision;
import com.storagehub.api.reservation.ReservationReviewRequest;
import com.storagehub.api.reservation.ReservationReviewResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationGoodsReviewService {

    private static final int PAYMENT_HOLD_MINUTES = 10;

    private final ReservationRepository reservationRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<ReservationReviewResponse> listPending(
        ActorPrincipal actor,
        UUID facilityId,
        int page,
        int size,
        String correlationId
    ) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0))
            : actor.facilityScopes().keySet().stream().toList();
        Page<ReservationReviewResponse> result = reservationRepository.findPendingGoodsReviews(
            facilityId, scoped, facilityIds, pageRequest(page, size)
        ).map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public ReservationReviewResponse review(
        ActorPrincipal actor,
        UUID reservationId,
        ReservationReviewRequest request
    ) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.APPROVE_RESERVATIONS);
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (reservation.getStatus() != ReservationStatus.AWAITING_REVIEW
            || reservation.getGoodsReviewStatus() != GoodsReviewStatus.PENDING) {
            throw ApiExceptions.conflict("Reservation is not awaiting goods review");
        }

        Instant now = Instant.now();
        if (reservation.getGoodsReviewDueAt() == null
            || !reservation.getGoodsReviewDueAt().isAfter(now)) {
            throw ApiExceptions.conflict("Reservation goods review period has expired");
        }
        String note = clean(request.getNote());
        if (request.getDecision() == ReservationReviewDecision.REJECT && note == null) {
            throw ApiExceptions.validation("note is required when rejecting goods", null);
        }

        User reviewer = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        ReservationStatus previousStatus = reservation.getStatus();
        List<ReservationGoodsItem> items = goodsItemRepository
            .findAllByReservation_IdOrderByCreatedAtAsc(reservationId);

        reservation.setGoodsReviewedBy(reviewer);
        reservation.setGoodsReviewedAt(now);
        reservation.setGoodsReviewNote(note);
        if (request.getDecision() == ReservationReviewDecision.APPROVE) {
            approve(reservation, items, note, now);
        } else {
            reject(reservation, items, note, now);
        }
        goodsItemRepository.saveAll(items);
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        ReservationReviewResponse response = toResponse(saved);
        auditLogService.recordMutation(
            reviewer, "RESERVATION_GOODS_" + request.getDecision(), "Reservation",
            saved.getId(), saved.getFacility().getId(),
            Map.of("status", previousStatus, "goodsReviewStatus", GoodsReviewStatus.PENDING),
            Map.of("status", saved.getStatus(), "goodsReviewStatus", saved.getGoodsReviewStatus())
        );
        return response;
    }

    private void approve(
        Reservation reservation,
        List<ReservationGoodsItem> items,
        String note,
        Instant now
    ) {
        reservation.setGoodsReviewStatus(GoodsReviewStatus.APPROVED);
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        Instant paymentDueAt = now.plus(PAYMENT_HOLD_MINUTES, ChronoUnit.MINUTES);
        reservation.setPaymentExpiresAt(paymentDueAt);
        reservation.setHoldExpiresAt(paymentDueAt);
        for (ReservationGoodsItem item : items) {
            if (item.isRequiresStaffReview()) {
                item.setReviewStatus(GoodsReviewStatus.APPROVED);
                item.setReviewNote(note);
            }
        }
    }

    private void reject(
        Reservation reservation,
        List<ReservationGoodsItem> items,
        String note,
        Instant now
    ) {
        reservation.setGoodsReviewStatus(GoodsReviewStatus.REJECTED);
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRejectedAt(now);
        reservation.setRejectionReason(note);
        for (ReservationGoodsItem item : items) {
            if (item.isRequiresStaffReview()) {
                item.setReviewStatus(GoodsReviewStatus.REJECTED);
                item.setReviewNote(note);
            }
        }
    }

    private void requireStaffActor(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only staff can review reservation goods");
        }
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation(
                "page must be >= 0 and size must be between 1 and 100", null
            );
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "goodsReviewDueAt"));
    }

    private ReservationReviewResponse toResponse(Reservation reservation) {
        ReservationReviewResponse response = new ReservationReviewResponse();
        response.setReservationId(reservation.getId());
        response.setReservationCode(reservation.getReservationCode());
        response.setFacilityId(reservation.getFacility().getId());
        response.setUnitTypeId(reservation.getUnitType().getId());
        response.setCustomerId(reservation.getCustomer().getId());
        response.setCustomerEmail(reservation.getCustomer().getEmail());
        response.setReservationStatus(reservation.getStatus());
        response.setGoodsReviewStatus(reservation.getGoodsReviewStatus());
        response.setStartDate(reservation.getStartDate());
        response.setEndDate(reservation.getEndDate());
        response.setGoodsCondition(reservation.getGoodsCondition());
        response.setReviewDueAt(reservation.getGoodsReviewDueAt());
        response.setReviewedAt(reservation.getGoodsReviewedAt());
        response.setReviewNote(reservation.getGoodsReviewNote());
        response.setGoodsItems(goodsItemRepository
            .findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .stream().map(this::toItemResponse).toList());
        return response;
    }

    private ReservationGoodsItemResponse toItemResponse(ReservationGoodsItem item) {
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

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
