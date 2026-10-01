package com.storagehub.service;

import com.storagehub.api.reservation.AssignStorageUnitRequest;
import com.storagehub.api.reservation.UnitAssignmentResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
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
public class ReservationUnitAssignmentService {

    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<UnitAssignmentResponse> listAwaitingAssignment(
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
        Page<Reservation> reservations = reservationRepository
            .findReservationsAwaitingUnitAssignment(
                facilityId, scoped, facilityIds, pageRequest(page, size)
            );
        return PageResponse.from(reservations.map(this::toResponse), correlationId);
    }

    @Transactional
    public UnitAssignmentResponse assign(
        ActorPrincipal actor,
        UUID reservationId,
        AssignStorageUnitRequest request
    ) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.ASSIGN_UNITS);
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());

        if (reservation.getStatus() == ReservationStatus.UNIT_RESERVED
            && reservation.getAssignedUnit() != null
            && reservation.getAssignedUnit().getId().equals(request.getStorageUnitId())) {
            return toResponse(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
            || reservation.getAssignedUnit() != null) {
            throw ApiExceptions.conflict("Reservation is not awaiting unit assignment");
        }

        StorageUnit storageUnit = storageUnitRepository
            .findByIdForUpdate(request.getStorageUnitId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        validateStorageUnit(reservation, storageUnit);
        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));

        Instant now = Instant.now();
        storageUnit.setStatus(StorageUnitStatus.reserved);
        reservation.setAssignedUnit(storageUnit);
        reservation.setAssignedUnitBy(staff);
        reservation.setAssignedUnitAt(now);
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        storageUnitRepository.saveAndFlush(storageUnit);
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        UnitAssignmentResponse response = toResponse(saved);
        auditLogService.recordMutation(
            staff, "RESERVATION_UNIT_ASSIGNED", "Reservation", saved.getId(),
            saved.getFacility().getId(),
            Map.of("status", ReservationStatus.CONFIRMED),
            Map.of(
                "status", ReservationStatus.UNIT_RESERVED,
                "storageUnitId", storageUnit.getId(),
                "storageUnitCode", storageUnit.getCode()
            )
        );
        notificationService.createNotification(
            saved.getCustomer().getId(), NotificationType.RESERVATION,
            "Kho đã được cấp cho đơn đặt chỗ",
            "Đơn " + saved.getReservationCode() + " đã được cấp kho "
                + storageUnit.getCode() + ".",
            saved.getId()
        );
        return response;
    }

    private void validateStorageUnit(Reservation reservation, StorageUnit storageUnit) {
        if (!storageUnit.getFacility().getId().equals(reservation.getFacility().getId())) {
            throw ApiExceptions.validation("Storage unit does not belong to the reservation facility", null);
        }
        if (!storageUnit.getUnitType().getId().equals(reservation.getUnitType().getId())) {
            throw ApiExceptions.validation("Storage unit does not match the reservation unit type", null);
        }
        if (storageUnit.getStatus() != StorageUnitStatus.available) {
            throw ApiExceptions.conflict("Storage unit is not available");
        }
    }

    private void requireStaffActor(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only staff can assign storage units");
        }
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation(
                "page must be >= 0 and size must be between 1 and 100", null
            );
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startDate"));
    }

    private UnitAssignmentResponse toResponse(Reservation reservation) {
        UnitAssignmentResponse response = new UnitAssignmentResponse();
        response.setReservationId(reservation.getId());
        response.setReservationCode(reservation.getReservationCode());
        response.setReservationStatus(reservation.getStatus());
        response.setCustomerId(reservation.getCustomer().getId());
        response.setCustomerEmail(reservation.getCustomer().getEmail());
        response.setFacilityId(reservation.getFacility().getId());
        response.setUnitTypeId(reservation.getUnitType().getId());
        response.setStartDate(reservation.getStartDate());
        response.setEndDate(reservation.getEndDate());
        response.setAssignedAt(reservation.getAssignedUnitAt());
        if (reservation.getAssignedUnit() != null) {
            response.setStorageUnitId(reservation.getAssignedUnit().getId());
            response.setStorageUnitCode(reservation.getAssignedUnit().getCode());
            response.setStorageUnitStatus(reservation.getAssignedUnit().getStatus());
        }
        return response;
    }
}
