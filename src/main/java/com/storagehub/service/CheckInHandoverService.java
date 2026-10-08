package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CheckInResponse;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.api.checkin.MarkNoShowRequest;
import com.storagehub.api.checkin.RejectCheckInRequest;
import com.storagehub.api.checkin.ScheduleCheckInRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.NotificationType;
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
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
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
public class CheckInHandoverService {

    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final CheckInRepository checkInRepository;
    private final FileAssetRepository fileAssetRepository;
    private final RentalRepository rentalRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PageResponse<CheckInResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        String q,
        int page,
        int pageSize,
        String correlationId
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(new UUID(0, 0))
            : actor.facilityScopes().keySet().stream().toList();
        Page<CheckInResponse> result = reservationRepository.findCheckInWork(
            facilityId, clean(q), scoped, facilityIds,
            pageRequest(page, pageSize)
        ).map(reservation -> toResponse(
            reservation,
            checkInRepository.findByReservation_Id(reservation.getId()).orElse(null),
            latestAssignment(reservation.getId())
        ));
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public CheckInResponse schedule(
        ActorPrincipal actor,
        UUID reservationId,
        ScheduleCheckInRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Reservation is not ready to schedule check-in");
        }
        UnitAssignment assignment = activeAssignment(reservationId);
        StorageUnit unit = requireReservedAssignedUnit(reservation, assignment);
        if (rentalRepository.existsActiveRental(reservationId, unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("Reservation or storage unit already has an active rental");
        }

        User operator = requireActor(actor);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId).orElseGet(CheckIn::new);
        if (checkIn.getId() != null && checkIn.getStatus() == CheckInStatus.completed) {
            throw ApiExceptions.conflict("Check-in is already completed");
        }
        checkIn.setReservation(reservation);
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.scheduled);
        checkIn.setScheduledAt(request.scheduledAt());
        checkIn.setCheckedInAt(null);
        checkIn.setChecklistJson(null);
        checkIn.setReadinessNote(clean(request.note()));
        ReservationStatus previousStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);

        reservationRepository.saveAndFlush(reservation);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(
            operator, "CHECKIN_SCHEDULED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of("reservationStatus", previousStatus),
            Map.of(
                "reservationStatus", ReservationStatus.READY_FOR_CHECKIN,
                "scheduledAt", request.scheduledAt()
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Lịch nhận kho đã được xác nhận",
            "Đơn " + reservation.getReservationCode() + " có lịch nhận kho vào " + request.scheduledAt() + ".",
            reservation.getId()
        );
        return toResponse(reservation, saved, assignment);
    }

    @Transactional
    public CheckInResponse complete(
        ActorPrincipal actor,
        UUID checkInId,
        CompleteCheckInRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());

        if (checkIn.getStatus() == CheckInStatus.completed) {
            return toResponse(reservation, checkIn, latestAssignment(reservation.getId()));
        }
        if (checkIn.getStatus() != CheckInStatus.scheduled) {
            throw ApiExceptions.conflict("Check-in is not scheduled");
        }
        if (reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Reservation is not READY_FOR_CHECKIN");
        }
        UnitAssignment assignment = activeAssignment(reservation.getId());
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        assertReservationUnitMatch(reservation, assignment, unit);
        if (unit.getStatus() != StorageUnitStatus.reserved) {
            throw ApiExceptions.conflict("Storage unit is not reserved for handover");
        }
        if (rentalRepository.existsActiveRental(reservation.getId(), unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("Reservation or storage unit already has an active rental");
        }
        if (request.actualMeasurements().weightKg().compareTo(unit.getUnitType().getMaxLoadKg()) > 0) {
            throw ApiExceptions.conflict("Actual goods weight exceeds the storage unit limit");
        }
        if (request.actualMeasurements().actualVolumeM3().compareTo(unit.getUnitType().getVolumeM3()) > 0) {
            throw ApiExceptions.conflict("Actual goods volume exceeds the storage unit capacity");
        }
        if (!dimensionsFit(request, unit)) {
            throw ApiExceptions.conflict("Actual goods dimensions do not fit the storage unit");
        }
        validateVariance(reservation, request);
        validateEvidence(checkInId, request.evidenceReferences());

        User operator = requireActor(actor);
        Instant now = Instant.now();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.completed);
        checkIn.setCheckedInAt(now);
        checkIn.setChecklistJson(writeHandover(request));
        assignment.setStatus(UnitAssignmentStatus.COMPLETED);
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        unit.setStatus(StorageUnitStatus.assigned);

        storageUnitRepository.saveAndFlush(unit);
        reservationRepository.saveAndFlush(reservation);
        assignmentRepository.saveAndFlush(assignment);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(
            operator, "CHECKIN_HANDOVER_COMPLETED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of(
                "checkInStatus", CheckInStatus.scheduled,
                "reservationStatus", ReservationStatus.READY_FOR_CHECKIN,
                "assignmentStatus", UnitAssignmentStatus.ACTIVE,
                "storageUnitStatus", StorageUnitStatus.reserved
            ),
            Map.of(
                "checkInStatus", CheckInStatus.completed,
                "reservationStatus", ReservationStatus.AWAITING_CUSTOMER_RECEIPT,
                "assignmentStatus", UnitAssignmentStatus.COMPLETED,
                "storageUnitStatus", StorageUnitStatus.assigned
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Biên bản bàn giao đang chờ xác nhận",
            "Gian kho " + unit.getCode() + " của đơn " + reservation.getReservationCode()
                + " đã được nhân viên bàn giao và đang chờ bạn xác nhận.",
            reservation.getId()
        );
        return toResponse(reservation, saved, assignment);
    }

    @Transactional
    public CheckInResponse markNoShow(
        ActorPrincipal actor,
        UUID checkInId,
        MarkNoShowRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.scheduled) {
            throw ApiExceptions.conflict("Only a scheduled check-in can be marked no-show");
        }
        if (checkIn.getScheduledAt() != null && checkIn.getScheduledAt().isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Check-in appointment has not started yet");
        }
        User operator = requireActor(actor);
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.no_show);
        checkIn.setReadinessNote("NO_SHOW: " + request.reason().trim());
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(
            operator, "CHECKIN_NO_SHOW", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of("checkInStatus", CheckInStatus.scheduled),
            Map.of("checkInStatus", CheckInStatus.no_show, "reason", request.reason().trim())
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Ghi nhận không đến nhận kho",
            "Đơn " + reservation.getReservationCode() + " đã được ghi nhận không đến theo lịch hẹn.",
            reservation.getId()
        );
        return toResponse(reservation, saved, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse reject(
        ActorPrincipal actor,
        UUID checkInId,
        RejectCheckInRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.scheduled) {
            throw ApiExceptions.conflict("Only a scheduled check-in can be rejected");
        }
        if (reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Reservation is not READY_FOR_CHECKIN");
        }

        UnitAssignment assignment = activeAssignment(reservation.getId());
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        assertReservationUnitMatch(reservation, assignment, unit);
        if (unit.getStatus() != StorageUnitStatus.reserved) {
            throw ApiExceptions.conflict("Storage unit is not reserved for this check-in");
        }
        if (rentalRepository.existsActiveRental(reservation.getId(), unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("Reservation or storage unit already has an active rental");
        }
        validateEvidence(checkInId, request.evidenceReferences());

        User operator = requireActor(actor);
        Instant now = Instant.now();
        StorageUnitStatus previousUnitStatus = unit.getStatus();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.rejected);
        checkIn.setCheckedInAt(now);
        checkIn.setRejectionReason(request.reason().trim());
        checkIn.setRejectionDisposition(request.disposition());
        checkIn.setChecklistJson(writeRejection(request));

        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(operator);
        assignment.setCancelledAt(now);
        assignment.setCancelReason(request.reason().trim());
        assignment.setReleaseDisposition(request.disposition());
        assignment.setPreviousStorageUnitStatus(previousUnitStatus);

        if (request.disposition() == UnitReleaseDisposition.MAINTENANCE) {
            unit.setStatus(StorageUnitStatus.maintenance);
        } else {
            unit.setStatus(StorageUnitStatus.available);
            unit.setAvailableFrom(now);
        }
        unit.setLastReleasedAt(now);
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRejectedAt(now);
        reservation.setRejectionReason(request.reason().trim());
        reservation.setAssignedUnit(null);

        storageUnitRepository.saveAndFlush(unit);
        reservationRepository.saveAndFlush(reservation);
        assignmentRepository.saveAndFlush(assignment);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(
            operator, "CHECKIN_REJECTED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of(
                "checkInStatus", CheckInStatus.scheduled,
                "reservationStatus", ReservationStatus.READY_FOR_CHECKIN,
                "assignmentStatus", UnitAssignmentStatus.ACTIVE,
                "storageUnitStatus", previousUnitStatus
            ),
            Map.of(
                "checkInStatus", CheckInStatus.rejected,
                "reservationStatus", ReservationStatus.REJECTED,
                "assignmentStatus", UnitAssignmentStatus.CANCELLED,
                "storageUnitStatus", unit.getStatus(),
                "reason", request.reason().trim()
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Từ chối nhận kho",
            "Đơn " + reservation.getReservationCode() + " chưa thể nhận kho: " + request.reason().trim(),
            reservation.getId()
        );
        return toResponse(reservation, saved, assignment);
    }

    private StorageUnit requireReservedAssignedUnit(
        Reservation reservation,
        UnitAssignment assignment
    ) {
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        assertReservationUnitMatch(reservation, assignment, unit);
        if (unit.getStatus() != StorageUnitStatus.reserved) {
            throw ApiExceptions.conflict("Storage unit is not reserved for this reservation");
        }
        return unit;
    }

    private void assertReservationUnitMatch(
        Reservation reservation,
        UnitAssignment assignment,
        StorageUnit unit
    ) {
        if (reservation.getAssignedUnit() == null
            || !reservation.getAssignedUnit().getId().equals(unit.getId())
            || !assignment.getReservation().getId().equals(reservation.getId())) {
            throw ApiExceptions.conflict("Reservation, assignment and storage unit do not match");
        }
    }

    private UnitAssignment activeAssignment(UUID reservationId) {
        return assignmentRepository.findByReservation_IdAndStatus(
            reservationId, UnitAssignmentStatus.ACTIVE
        ).orElseThrow(() -> ApiExceptions.conflict("Reservation has no active unit assignment"));
    }

    private UnitAssignment latestAssignment(UUID reservationId) {
        return assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit assignment was not found"));
    }

    private User requireActor(ActorPrincipal actor) {
        return userRepository.findById(actor.userId())
            .orElseThrow(() -> ApiExceptions.unauthorized("The actor no longer exists"));
    }

    private CheckInResponse toResponse(
        Reservation reservation,
        CheckIn checkIn,
        UnitAssignment assignment
    ) {
        StorageUnit unit = assignment.getStorageUnit();
        return new CheckInResponse(
            checkIn == null ? null : checkIn.getId(),
            checkIn == null ? null : checkIn.getStatus(),
            checkIn == null ? null : checkIn.getScheduledAt(),
            checkIn == null ? null : checkIn.getCheckedInAt(),
            checkIn == null ? null : checkIn.getReadinessNote(),
            checkIn == null ? null : checkIn.getRejectionReason(),
            checkIn == null ? null : checkIn.getRejectionDisposition(),
            checkIn == null ? null : checkIn.getPerformedBy().getId(),
            checkIn == null ? null : checkIn.getPerformedBy().getFullName(),
            reservation.getId(), reservation.getReservationCode(), reservation.getStatus(),
            reservation.getCustomer().getId(), reservation.getCustomer().getFullName(),
            reservation.getCustomer().getEmail(), reservation.getFacility().getId(),
            reservation.getFacility().getName(), unit.getId(), unit.getCode(), unit.getStatus(),
            assignment.getId(), assignment.getStatus(), reservation.getStartDate(),
            reservation.getEndDate(), reservation.getTotalGoodsWeightKg(),
            reservation.getTotalGoodsVolumeM3(), readHandover(checkIn)
        );
    }

    private String writeHandover(CompleteCheckInRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize check-in handover", exception);
        }
    }

    private String writeRejection(RejectCheckInRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize check-in rejection", exception);
        }
    }

    private boolean dimensionsFit(CompleteCheckInRequest request, StorageUnit unit) {
        BigDecimal[] goodsDimensions = {
            request.actualMeasurements().lengthCm(),
            request.actualMeasurements().widthCm(),
            request.actualMeasurements().heightCm()
        };
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal[] unitDimensions = {
            unit.getUnitType().getLengthM().multiply(hundred),
            unit.getUnitType().getWidthM().multiply(hundred),
            unit.getUnitType().getHeightM().multiply(hundred)
        };
        Arrays.sort(goodsDimensions);
        Arrays.sort(unitDimensions);
        for (int index = 0; index < goodsDimensions.length; index++) {
            if (goodsDimensions[index].compareTo(unitDimensions[index]) > 0) {
                return false;
            }
        }
        return true;
    }

    private CompleteCheckInRequest readHandover(CheckIn checkIn) {
        if (checkIn == null || checkIn.getStatus() != CheckInStatus.completed
            || checkIn.getChecklistJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(checkIn.getChecklistJson(), CompleteCheckInRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read check-in handover", exception);
        }
    }

    private void validateVariance(Reservation reservation, CompleteCheckInRequest request) {
        boolean weightVaries = exceedsTolerance(
            request.actualMeasurements().weightKg(), reservation.getTotalGoodsWeightKg(), new BigDecimal("0.10")
        );
        boolean volumeVaries = exceedsTolerance(
            request.actualMeasurements().actualVolumeM3(), reservation.getTotalGoodsVolumeM3(), new BigDecimal("0.000100")
        );
        if (!weightVaries && !volumeVaries) {
            return;
        }
        if (!request.actualMeasurements().varianceAccepted()) {
            throw ApiExceptions.conflict("Actual goods differ from the reservation and the variance is not accepted");
        }
        if (request.varianceReason() == null || request.varianceReason().isBlank()) {
            throw ApiExceptions.validation("varianceReason is required when actual goods differ", null);
        }
    }

    private boolean exceedsTolerance(BigDecimal actual, BigDecimal declared, BigDecimal minimumTolerance) {
        if (declared == null) {
            return false;
        }
        BigDecimal tolerance = declared.abs().multiply(new BigDecimal("0.05")).max(minimumTolerance);
        return actual.subtract(declared).abs().compareTo(tolerance) > 0;
    }

    private void validateEvidence(UUID checkInId, List<String> references) {
        List<UUID> assetIds;
        try {
            assetIds = references.stream().map(UUID::fromString).distinct().toList();
        } catch (IllegalArgumentException exception) {
            throw ApiExceptions.validation("Every evidence reference must be a file asset ID", null);
        }
        if (assetIds.size() != references.size()) {
            throw ApiExceptions.validation("Evidence references must be unique", null);
        }
        List<FileAsset> assets = fileAssetRepository.findAllById(assetIds);
        boolean valid = assets.size() == assetIds.size() && assets.stream().allMatch(asset ->
            "CHECK_IN".equals(asset.getEntityType())
                && checkInId.equals(asset.getEntityId())
                && asset.getStatus() == FileAssetStatus.ACTIVE
        );
        if (!valid) {
            throw ApiExceptions.validation("Evidence must contain active files linked to this check-in", null);
        }
    }

    private PageRequest pageRequest(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw ApiExceptions.validation("page must be >= 0 and pageSize must be between 1 and 100", null);
        }
        return PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "startDate"));
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireOperator(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            throw ApiExceptions.forbidden("Only operations staff can process check-in and handover");
        }
    }
}
