package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CheckInResponse;
import com.storagehub.api.checkin.CheckInPaymentRequest;
import com.storagehub.api.checkin.CheckInPaymentResponse;
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
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
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
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckInHandoverService {

    @Autowired
    private AccessCredentialService accessCredentialService;

    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final CheckInRepository checkInRepository;
    private final FileAssetRepository fileAssetRepository;
    private final RentalRepository rentalRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final ReservationPricingSnapshotRepository pricingSnapshotRepository;
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
        String status,
        Instant scheduledFrom,
        Instant scheduledTo,
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
        CheckInListFilter statusFilter = CheckInListFilter.parse(status);
        if (scheduledFrom != null && scheduledTo != null && !scheduledFrom.isBefore(scheduledTo)) {
            throw ApiExceptions.validation(
                "scheduledFrom must be before scheduledTo",
                Map.of("scheduledFrom", scheduledFrom, "scheduledTo", scheduledTo)
            );
        }
        Page<CheckInResponse> result = reservationRepository.findCheckInWork(
            facilityId, clean(q), statusFilter.enabled(), statusFilter.unscheduled(),
            statusFilter.checkInStatus(), scheduledFrom, scheduledTo, scoped, facilityIds,
            pageRequest(page, pageSize)
        ).map(reservation -> toResponse(
            reservation,
            checkInRepository.findByReservation_Id(reservation.getId()).orElse(null),
            latestAssignment(reservation.getId())
        ));
        return PageResponse.from(result, correlationId);
    }

    private record CheckInListFilter(
        boolean enabled,
        boolean unscheduled,
        CheckInStatus checkInStatus
    ) {
        private static CheckInListFilter parse(String value) {
            if (value == null || value.isBlank()) {
                return new CheckInListFilter(false, false, null);
            }
            if ("UNSCHEDULED".equalsIgnoreCase(value.trim())) {
                return new CheckInListFilter(true, true, null);
            }
            try {
                return new CheckInListFilter(
                    true,
                    false,
                    CheckInStatus.valueOf(value.trim().toLowerCase())
                );
            } catch (IllegalArgumentException exception) {
                throw ApiExceptions.validation(
                    "Unsupported check-in status filter",
                    Map.of("status", value)
                );
            }
        }
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
        // The appointment belongs to the customer. Keep the request field for API
        // compatibility, but never let a staff actor move the customer appointment.
        Instant appointmentAt = reservation.getAppointmentAt();
        if (appointmentAt == null) {
            // Legacy reservations created before appointmentAt was persisted can
            // still be scheduled once; the value is then pinned for subsequent runs.
            appointmentAt = request.scheduledAt();
            reservation.setAppointmentAt(appointmentAt);
        }
        checkIn.setScheduledAt(appointmentAt);
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
                "scheduledAt", appointmentAt
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Lịch nhận kho đã được xác nhận",
            "Đơn " + reservation.getReservationCode() + " có lịch nhận kho vào " + appointmentAt + ".",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Lịch nhận kho đã được xác nhận",
            "Đơn " + reservation.getReservationCode() + " có lịch nhận kho vào " + appointmentAt + ".",
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
        requireRemainingPayment(reservation);
        if (accessCredentialService != null
            && !accessCredentialService.hasActiveForReservation(reservation.getId())) {
            throw ApiExceptions.conflict("At least one active access credential must be issued before handover");
        }

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
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Bàn giao đã hoàn tất, chờ khách xác nhận",
            "Đơn " + reservation.getReservationCode()
                + " đã bàn giao gian kho và đang chờ khách hàng xác nhận nhận kho.",
            reservation.getId()
        );
        return toResponse(reservation, saved, assignment);
    }

    @Transactional
    public CheckInPaymentResponse recordRemainingPayment(
        ActorPrincipal actor,
        UUID checkInId,
        CheckInPaymentRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.scheduled
            || reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN) {
            throw ApiExceptions.conflict("Check-in is not ready to record the remaining payment");
        }
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        if (request.amount().compareTo(snapshot.getDueAtCheckIn()) != 0) {
            throw ApiExceptions.validation("Payment amount must match the remaining check-in amount", Map.of("expectedAmount", snapshot.getDueAtCheckIn()));
        }
        String paymentMethod = request.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!paymentMethod.equals("CASH") && !paymentMethod.equals("BANK_TRANSFER")) {
            throw ApiExceptions.validation("paymentMethod must be CASH or BANK_TRANSFER", Map.of("paymentMethod", request.paymentMethod()));
        }
        String reference = request.transactionReference().trim();
        var existing = paymentRepository.findTopByReservation_IdAndPurposeAndGatewayTransactionNo(
            reservation.getId(), PaymentType.RENTAL_BALANCE, reference
        );
        if (existing.isPresent()) {
            return toPaymentResponse(existing.get());
        }

        User operator = requireActor(actor);
        Instant now = Instant.now();
        Payment payment = new Payment();
        payment.setInitiatedBy(operator);
        payment.setReservation(reservation);
        payment.setAmount(request.amount());
        payment.setCurrency("VND");
        payment.setPurpose(PaymentType.RENTAL_BALANCE);
        payment.setStatus(PaymentStatus.PAID);
        payment.setIdempotencyKey("CHECKIN-BALANCE-" + checkInId + "-" + reference);
        payment.setGatewayIntentId("MANUAL-CHECKIN-" + UUID.randomUUID());
        payment.setGatewayProvider("MANUAL");
        payment.setGatewayTransactionNo(reference);
        payment.setGatewayBankCode(paymentMethod);
        payment.setGatewayResponseCode("00");
        payment.setGatewayTransactionStatus("PAID");
        payment.setPaidAt(now);
        payment.setProcessedAt(now);
        Payment saved = paymentRepository.saveAndFlush(payment);
        auditLogService.recordMutation(
            operator, "CHECKIN_REMAINING_PAYMENT_RECORDED", "Payment", saved.getId(),
            reservation.getFacility().getId(), null, toPaymentResponse(saved)
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.PAYMENT,
            "Đã ghi nhận thanh toán khi nhận kho",
            "Đơn " + reservation.getReservationCode() + " đã có biên lai phần thanh toán còn lại.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.PAYMENT,
            "Đã ghi nhận thanh toán khi nhận kho",
            "Đơn " + reservation.getReservationCode() + " đã có biên lai phần thanh toán còn lại.",
            reservation.getId()
        );
        return toPaymentResponse(saved);
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
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách hàng không đến nhận kho",
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
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Đã từ chối bàn giao kho",
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
        BigDecimal[] declaredDimensions = declaredGoodsDimensions(reservation.getId());
        return new CheckInResponse(
            checkIn == null ? null : checkIn.getId(),
            checkIn == null ? null : checkIn.getStatus(),
            checkIn == null ? null : checkIn.getScheduledAt(),
            reservation.getAppointmentAt(),
            checkIn == null ? null : checkIn.getCheckedInAt(),
            checkIn == null ? null : checkIn.getReadinessNote(),
            checkIn == null ? null : checkIn.getRejectionReason(),
            checkIn == null ? null : checkIn.getRejectionDisposition(),
            checkIn == null ? null : checkIn.getPerformedBy().getId(),
            checkIn == null ? null : checkIn.getPerformedBy().getFullName(),
            reservation.getId(), reservation.getReservationCode(), reservation.getStatus(),
            reservation.getCustomer().getId(), reservation.getCustomer().getFullName(),
            reservation.getCustomer().getEmail(), reservation.getCustomer().getPhone(), reservation.getFacility().getId(),
            reservation.getFacility().getName(), unit.getId(), unit.getCode(), unit.getStatus(),
            assignment.getId(), assignment.getStatus(), reservation.getStartDate(),
            reservation.getEndDate(), declaredPackageCount(reservation.getId()),
            reservation.getTotalGoodsWeightKg(),
            reservation.getTotalGoodsVolumeM3(), declaredDimensions[0], declaredDimensions[1],
            declaredDimensions[2], dueAtCheckIn(reservation.getId()), readHandover(checkIn)
        );
    }

    private BigDecimal dueAtCheckIn(UUID reservationId) {
        if (pricingSnapshotRepository == null) {
            return null;
        }
        return pricingSnapshotRepository.findByReservation_Id(reservationId)
            .map(snapshot -> snapshot.getDueAtCheckIn())
            .orElse(null);
    }

    private void requireRemainingPayment(Reservation reservation) {
        if (pricingSnapshotRepository == null || paymentRepository == null) {
            return;
        }
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (snapshot == null || snapshot.getDueAtCheckIn() == null
            || snapshot.getDueAtCheckIn().signum() <= 0) {
            return;
        }
        var payment = paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
            reservation.getId(), PaymentType.RENTAL_BALANCE
        ).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PAID
            || payment.getAmount() == null
            || payment.getAmount().compareTo(snapshot.getDueAtCheckIn()) != 0) {
            throw ApiExceptions.conflict("Remaining check-in payment must be recorded before handover");
        }
    }

    private CheckInPaymentResponse toPaymentResponse(Payment payment) {
        return new CheckInPaymentResponse(
            payment.getId(), payment.getReservation().getId(), payment.getAmount(), payment.getCurrency(),
            payment.getPurpose(), payment.getStatus(), payment.getGatewayTransactionNo(),
            payment.getGatewayBankCode(), payment.getPaidAt(), payment.getProcessedAt(),
            payment.getInitiatedBy().getFullName()
        );
    }

    private Integer declaredPackageCount(UUID reservationId) {
        var goodsItems = goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservationId);
        if (goodsItems.isEmpty()) {
            return null;
        }
        return goodsItems.stream().mapToInt(item -> item.getQuantity()).sum();
    }

    /**
     * Returns the largest declared package dimensions, orientation-normalized.
     * These values are shown to the operator and are also used to prevent a
     * handover from silently accepting a materially different package size.
     */
    private BigDecimal[] declaredGoodsDimensions(UUID reservationId) {
        BigDecimal[] largest = {null, null, null};
        for (var item : goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservationId)) {
            if (item.getLengthCm() == null || item.getWidthCm() == null || item.getHeightCm() == null) {
                continue;
            }
            BigDecimal[] dimensions = {item.getLengthCm(), item.getWidthCm(), item.getHeightCm()};
            Arrays.sort(dimensions);
            for (int index = 0; index < dimensions.length; index++) {
                if (largest[index] == null || dimensions[index].compareTo(largest[index]) > 0) {
                    largest[index] = dimensions[index];
                }
            }
        }
        return largest;
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
        Integer declaredPackageCount = declaredPackageCount(reservation.getId());
        boolean packageCountVaries = declaredPackageCount != null
            && request.packageCount() != declaredPackageCount;
        boolean weightVaries = exceedsTolerance(
            request.actualMeasurements().weightKg(), reservation.getTotalGoodsWeightKg(), new BigDecimal("0.10")
        );
        boolean volumeVaries = exceedsTolerance(
            request.actualMeasurements().actualVolumeM3(), reservation.getTotalGoodsVolumeM3(), new BigDecimal("0.000100")
        );
        BigDecimal[] declaredDimensions = declaredGoodsDimensions(reservation.getId());
        boolean dimensionsVaries = false;
        if (declaredDimensions[0] != null) {
            BigDecimal[] actualDimensions = {
                request.actualMeasurements().lengthCm(),
                request.actualMeasurements().widthCm(),
                request.actualMeasurements().heightCm()
            };
            Arrays.sort(actualDimensions);
            for (int index = 0; index < actualDimensions.length; index++) {
                if (exceedsTolerance(actualDimensions[index], declaredDimensions[index], new BigDecimal("0.5"))) {
                    dimensionsVaries = true;
                    break;
                }
            }
        }
        if (!packageCountVaries && !weightVaries && !volumeVaries && !dimensionsVaries) {
            return;
        }
        if (!request.actualMeasurements().varianceAccepted()) {
            throw ApiExceptions.conflict("Actual goods dimensions or measurements differ from the reservation and the variance is not accepted");
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
