package com.storagehub.service;

import com.storagehub.api.reservation.StaffReservationProgressResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StaffReservationQueryService {

    private static final List<ReservationStatus> ACTIVE_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW,
        ReservationStatus.AWAITING_PAYMENT,
        ReservationStatus.PAYMENT_GRACE,
        ReservationStatus.PAYMENT_REVIEW,
        ReservationStatus.CONFIRMED,
        ReservationStatus.UNIT_RESERVED,
        ReservationStatus.READY_FOR_CHECKIN,
        ReservationStatus.AWAITING_CUSTOMER_RECEIPT
    );

    private final ReservationRepository reservationRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;

    @Transactional(readOnly = true)
    public PageResponse<StaffReservationProgressResponse> listActive(
        ActorPrincipal actor,
        UUID facilityId,
        ReservationStatus status,
        String query,
        int page,
        int size,
        String correlationId
    ) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only operations staff can view active reservations");
        }
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }
        if (status != null && !ACTIVE_STATUSES.contains(status)) {
            throw ApiExceptions.validation("status must be an active reservation status", null);
        }
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and size must be between 1 and 100", null);
        }

        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0))
            : actor.facilityScopes().keySet().stream().toList();
        var result = reservationRepository.findStaffActiveReservations(
            ACTIVE_STATUSES, facilityId, status, clean(query), scoped, facilityIds,
            PageRequest.of(page, size)
        ).map(this::toResponse);
        return PageResponse.from(result, correlationId);
    }

    private StaffReservationProgressResponse toResponse(Reservation reservation) {
        var unit = reservation.getAssignedUnit();
        return new StaffReservationProgressResponse(
            reservation.getId(), reservation.getReservationCode(),
            reservation.getCustomer().getId(), reservation.getCustomer().getFullName(),
            reservation.getCustomer().getEmail(), reservation.getFacility().getId(),
            reservation.getFacility().getName(), reservation.getUnitType().getId(),
            reservation.getUnitType().getName(), unit == null ? null : unit.getId(),
            unit == null ? null : unit.getCode(), reservation.getStatus(),
            reservation.getGoodsReviewStatus(), reservation.getStartDate(), reservation.getEndDate(),
            reservation.getAppointmentAt(), reservation.getHoldExpiresAt(),
            reservation.getPaymentExpiresAt(), reservation.getCreatedAt()
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
