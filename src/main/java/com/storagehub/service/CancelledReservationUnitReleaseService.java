package com.storagehub.service;

import com.storagehub.api.unitrelease.ReleaseAssignedUnitRequest;
import com.storagehub.api.unitrelease.UnitReleaseCaseResponse;
import com.storagehub.api.unitrelease.UnitReleaseResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
public class CancelledReservationUnitReleaseService {

    private final UnitAssignmentRepository assignmentRepository;
    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final CheckInRepository checkInRepository;
    private final RentalRepository rentalRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public PageResponse<UnitReleaseCaseResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        UUID unitTypeId,
        String q,
        Boolean blocked,
        int page,
        int pageSize,
        String correlationId
    ) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }

        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0))
            : actor.facilityScopes().keySet().stream().toList();
        PageRequest pageable = pageRequest(page, pageSize);
        Page<UnitReleaseCaseResponse> result = assignmentRepository.findPendingReleases(
            facilityId, unitTypeId, normalizeQuery(q), blocked, scoped, facilityIds, pageable
        ).map(this::toCaseResponse);
        return PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public UnitReleaseCaseResponse get(ActorPrincipal actor, UUID reservationId) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.VIEW_RESERVATIONS);
        authorizationService.require(actor, SystemPermission.VIEW_UNITS);
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        authorizationService.require(actor, SystemPermission.VIEW_RENTALS);

        UnitAssignment assignment = assignmentRepository
            .findByReservation_IdAndStatus(reservationId, UnitAssignmentStatus.ACTIVE)
            .orElseThrow(() -> ApiExceptions.notFound("Active unit assignment was not found"));
        facilityScopeService.assertCanRead(actor, assignment.getReservation().getFacility().getId());
        return toCaseResponse(assignment);
    }

    @Transactional
    public UnitReleaseResponse release(
        ActorPrincipal actor,
        UUID reservationId,
        String idempotencyKey,
        ReleaseAssignedUnitRequest request
    ) {
        requireStaffActor(actor);
        authorizationService.require(actor, SystemPermission.ASSIGN_UNITS);
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(reservationId, request);

        var prior = assignmentRepository.findByCancelledBy_IdAndCancellationIdempotencyKey(
            actor.userId(), idempotencyKey
        );
        if (prior.isPresent()) {
            UnitAssignment assignment = prior.get();
            facilityScopeService.assertCanOperate(
                actor, assignment.getReservation().getFacility().getId()
            );
            if (!fingerprint.equals(assignment.getCancellationRequestFingerprint())) {
                throw ApiExceptions.conflict("Idempotency key was already used with a different request");
            }
            return toReleaseResponse(assignment);
        }

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (reservation.getStatus() != ReservationStatus.CANCELLED) {
            throw ApiExceptions.conflict("Reservation is not cancelled");
        }

        UnitAssignment assignment = assignmentRepository.findByIdForUpdate(request.assignmentId())
            .orElseThrow(() -> ApiExceptions.notFound("Unit assignment was not found"));
        if (!assignment.getReservation().getId().equals(reservationId)) {
            throw ApiExceptions.conflict("Unit assignment does not belong to the reservation");
        }
        if (assignment.getStatus() != UnitAssignmentStatus.ACTIVE) {
            throw ApiExceptions.conflict("Unit assignment is not active");
        }

        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        if (reservation.getAssignedUnit() == null
            || !reservation.getAssignedUnit().getId().equals(unit.getId())) {
            throw ApiExceptions.conflict("Reservation and active unit assignment do not match");
        }
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId).orElse(null);
        if (checkIn != null && checkIn.getStatus() == CheckInStatus.completed) {
            throw ApiExceptions.conflict("The assigned unit cannot be released because check-in is completed");
        }
        if (rentalRepository.existsActiveRental(reservationId, unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("The assigned unit cannot be released because an active rental exists");
        }
        if (unit.getStatus() != StorageUnitStatus.reserved
            && unit.getStatus() != StorageUnitStatus.assigned) {
            throw ApiExceptions.conflict("Storage unit is not in a releasable state");
        }

        User staff = userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
        Instant now = Instant.now();
        StorageUnitStatus previousStatus = unit.getStatus();
        if (request.disposition() == UnitReleaseDisposition.MAINTENANCE) {
            unit.setStatus(StorageUnitStatus.maintenance);
        } else {
            unit.setStatus(StorageUnitStatus.available);
            unit.setAvailableFrom(now);
        }
        unit.setLastReleasedAt(now);

        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(staff);
        assignment.setCancelledAt(now);
        assignment.setCancelReason(request.reason().trim());
        assignment.setCancellationIdempotencyKey(idempotencyKey.trim());
        assignment.setCancellationRequestFingerprint(fingerprint);
        assignment.setReleaseDisposition(request.disposition());
        assignment.setPreviousStorageUnitStatus(previousStatus);
        reservation.setAssignedUnit(null);
        if (checkIn != null && checkIn.getStatus() == CheckInStatus.scheduled) {
            checkIn.setStatus(CheckInStatus.cancelled);
            checkInRepository.saveAndFlush(checkIn);
        }

        storageUnitRepository.saveAndFlush(unit);
        reservationRepository.saveAndFlush(reservation);
        UnitAssignment saved = assignmentRepository.saveAndFlush(assignment);

        auditLogService.recordMutation(
            staff, "RESERVATION_UNIT_RELEASED", "UnitAssignment", saved.getId(),
            reservation.getFacility().getId(),
            Map.of("assignmentStatus", UnitAssignmentStatus.ACTIVE, "storageUnitStatus", previousStatus),
            Map.of(
                "assignmentStatus", UnitAssignmentStatus.CANCELLED,
                "storageUnitStatus", unit.getStatus(),
                "disposition", request.disposition()
            )
        );
        return toReleaseResponse(saved);
    }

    private UnitReleaseCaseResponse toCaseResponse(UnitAssignment assignment) {
        Reservation reservation = assignment.getReservation();
        StorageUnit unit = assignment.getStorageUnit();
        boolean reservationCancelled = reservation.getStatus() == ReservationStatus.CANCELLED;
        boolean activeAssignment = assignment.getStatus() == UnitAssignmentStatus.ACTIVE;
        boolean checkInCompleted = checkInRepository.existsByReservation_IdAndStatus(
            reservation.getId(), CheckInStatus.completed
        );
        boolean activeRental = rentalRepository.existsActiveRental(
            reservation.getId(), unit.getId(), RentalStatus.active
        );
        List<String> blockers = new ArrayList<>();
        if (!reservationCancelled) blockers.add("RESERVATION_NOT_CANCELLED");
        if (!activeAssignment) blockers.add("ACTIVE_ASSIGNMENT_NOT_FOUND");
        if (checkInCompleted) blockers.add("CHECKIN_ALREADY_COMPLETED");
        if (activeRental) blockers.add("ACTIVE_RENTAL_EXISTS");
        if (reservation.getAssignedUnit() == null
            || !reservation.getAssignedUnit().getId().equals(unit.getId())) {
            blockers.add("UNIT_ASSIGNMENT_MISMATCH");
        }
        if (unit.getStatus() != StorageUnitStatus.reserved
            && unit.getStatus() != StorageUnitStatus.assigned) {
            blockers.add("UNIT_STATE_NOT_RELEASABLE");
        }

        return new UnitReleaseCaseResponse(
            reservation.getId(), reservation.getReservationCode(), reservation.getStatus(),
            reservation.getCancelledAt(), reservation.getCancelReason(),
            reservation.getFacility().getId(), reservation.getFacility().getName(),
            reservation.getUnitType().getId(),
            new UnitReleaseCaseResponse.Assignment(
                assignment.getId(), assignment.getStatus(), assignment.getAssignedAt(),
                unit.getId(), unit.getCode(), unit.getStatus()
            ),
            new UnitReleaseCaseResponse.Eligibility(
                blockers.isEmpty(), reservationCancelled, activeAssignment, checkInCompleted,
                activeRental, List.copyOf(blockers)
            )
        );
    }

    private UnitReleaseResponse toReleaseResponse(UnitAssignment assignment) {
        StorageUnit unit = assignment.getStorageUnit();
        return new UnitReleaseResponse(
            assignment.getReservation().getId(), assignment.getReservation().getStatus(),
            assignment.getId(), assignment.getStatus(), assignment.getCancelledAt(),
            unit.getId(), unit.getCode(), assignment.getPreviousStorageUnitStatus(), unit.getStatus(),
            assignment.getReleaseDisposition(),
            assignment.getCancelledBy().getId(), assignment.getCancelledAt()
        );
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiExceptions.validation("Idempotency-Key is required and must not exceed 100 characters", null);
        }
    }

    private void requireStaffActor(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only staff actors can process unit releases");
        }
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiExceptions.validation("page must be >= 0 and pageSize must be between 1 and 100", null);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "reservation.cancelledAt"));
    }

    private String normalizeQuery(String q) {
        return q == null || q.isBlank() ? null : q.trim();
    }

    private String fingerprint(UUID reservationId, ReleaseAssignedUnitRequest request) {
        String value = String.join("|", reservationId.toString(), request.assignmentId().toString(),
            request.disposition().name(), request.reason().trim());
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
