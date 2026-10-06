package com.storagehub.service;

import com.storagehub.api.unitassignment.AssignableUnitResponse;
import com.storagehub.api.unitassignment.CreateUnitAssignmentRequest;
import com.storagehub.api.unitassignment.UnitAssignmentCandidateResponse;
import com.storagehub.api.unitassignment.UnitAssignmentResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
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
public class UnitAssignmentService {

    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<UnitAssignmentCandidateResponse> listCandidates(
        ActorPrincipal actor,
        UUID facilityId,
        UUID unitTypeId,
        String q,
        int page,
        int pageSize,
        String correlationId
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0))
            : actor.facilityScopes().keySet().stream().toList();
        Page<UnitAssignmentCandidateResponse> result = reservationRepository
            .findUnitAssignmentCandidates(
                facilityId, unitTypeId, clean(q), scoped, facilityIds,
                pageRequest(page, pageSize, Sort.by(Sort.Direction.ASC, "confirmedAt"))
            )
            .map(this::toCandidateResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignableUnitResponse> listAvailableUnits(
        ActorPrincipal actor,
        UUID reservationId,
        String q,
        int page,
        int pageSize,
        String correlationId
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanRead(actor, reservation.getFacility().getId());
        assertAssignmentCandidate(reservation);
        Page<AssignableUnitResponse> result = storageUnitRepository.findAssignableUnits(
            reservation.getFacility().getId(), reservation.getUnitType().getId(), clean(q),
            pageRequest(page, pageSize, Sort.by(Sort.Direction.ASC, "code"))
        ).map(this::toUnitResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public UnitAssignmentResponse assign(
        ActorPrincipal actor,
        UUID reservationId,
        CreateUnitAssignmentRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.ASSIGN_UNITS);

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());

        var current = assignmentRepository.findByReservation_IdAndStatus(
            reservationId, UnitAssignmentStatus.ACTIVE
        );
        if (current.isPresent()) {
            UnitAssignment assignment = current.get();
            if (assignment.getStorageUnit().getId().equals(request.storageUnitId())) {
                return toAssignmentResponse(assignment);
            }
            throw ApiExceptions.conflict("Reservation already has an active unit assignment");
        }
        assertAssignmentCandidate(reservation);

        StorageUnit unit = storageUnitRepository.findByIdForUpdate(request.storageUnitId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        if (!unit.getFacility().getId().equals(reservation.getFacility().getId())) {
            throw ApiExceptions.conflict("Storage unit belongs to a different facility");
        }
        if (!unit.getUnitType().getId().equals(reservation.getUnitType().getId())) {
            throw ApiExceptions.conflict("Storage unit type does not match the reservation");
        }
        if (unit.getStatus() != StorageUnitStatus.available) {
            throw ApiExceptions.conflict("Storage unit is not available");
        }
        if (assignmentRepository.existsByStorageUnit_IdAndStatus(
            unit.getId(), UnitAssignmentStatus.ACTIVE
        )) {
            throw ApiExceptions.conflict("Storage unit already has an active assignment");
        }

        User operator = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Instant now = Instant.now();
        UnitAssignment assignment = new UnitAssignment();
        assignment.setReservation(reservation);
        assignment.setStorageUnit(unit);
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);
        assignment.setAssignedBy(operator);
        assignment.setAssignedAt(now);

        reservation.setAssignedUnit(unit);
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        unit.setStatus(StorageUnitStatus.reserved);
        unit.setAvailableFrom(null);

        storageUnitRepository.saveAndFlush(unit);
        reservationRepository.saveAndFlush(reservation);
        UnitAssignment saved = assignmentRepository.saveAndFlush(assignment);
        auditLogService.recordMutation(
            operator, "UNIT_ASSIGNED_TO_RESERVATION", "UnitAssignment", saved.getId(),
            reservation.getFacility().getId(),
            Map.of(
                "reservationStatus", ReservationStatus.CONFIRMED,
                "storageUnitStatus", StorageUnitStatus.available
            ),
            Map.of(
                "reservationStatus", ReservationStatus.UNIT_RESERVED,
                "storageUnitStatus", StorageUnitStatus.reserved,
                "storageUnitId", unit.getId()
            )
        );
        return toAssignmentResponse(saved);
    }

    private void assertAssignmentCandidate(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw ApiExceptions.conflict("Reservation is not confirmed");
        }
        if (reservation.getAssignedUnit() != null) {
            throw ApiExceptions.conflict("Reservation already has an assigned unit");
        }
    }

    private UnitAssignmentCandidateResponse toCandidateResponse(Reservation reservation) {
        return new UnitAssignmentCandidateResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getStatus(),
            reservation.getCustomer().getId(), reservation.getCustomer().getFullName(),
            reservation.getCustomer().getEmail(), reservation.getFacility().getId(),
            reservation.getFacility().getName(), reservation.getUnitType().getId(),
            reservation.getUnitType().getName(), reservation.getStartDate(), reservation.getEndDate(),
            reservation.getConfirmedAt(), reservation.getTotalGoodsVolumeM3(),
            reservation.getTotalGoodsWeightKg()
        );
    }

    private AssignableUnitResponse toUnitResponse(StorageUnit unit) {
        return new AssignableUnitResponse(
            unit.getId(), unit.getCode(), unit.getFloor(), unit.getZone(), unit.getStatus()
        );
    }

    private UnitAssignmentResponse toAssignmentResponse(UnitAssignment assignment) {
        Reservation reservation = assignment.getReservation();
        StorageUnit unit = assignment.getStorageUnit();
        return new UnitAssignmentResponse(
            assignment.getId(), assignment.getStatus(), assignment.getAssignedAt(),
            assignment.getAssignedBy().getId(), reservation.getId(), reservation.getReservationCode(),
            reservation.getStatus(), unit.getId(), unit.getCode(), unit.getStatus()
        );
    }

    private PageRequest pageRequest(int page, int pageSize, Sort sort) {
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw ApiExceptions.validation("page must be >= 0 and pageSize must be between 1 and 100", null);
        }
        return PageRequest.of(page, pageSize, sort);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireOperator(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only operations staff can assign storage units");
        }
    }
}
