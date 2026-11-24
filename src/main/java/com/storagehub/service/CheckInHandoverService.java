package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CheckInResponse;
import com.storagehub.api.checkin.CheckInResponse.IntakeAction;
import com.storagehub.api.checkin.ReceiptReminderResponse;
import com.storagehub.common.api.ApiException;
import com.storagehub.api.checkin.CheckInPaymentRequest;
import com.storagehub.api.checkin.CheckInPaymentResponse;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.api.checkin.CompleteCheckInSetupRequest;
import com.storagehub.api.checkin.ConfirmInventoryPartialRemovalRequest;
import com.storagehub.api.checkin.ConfirmCheckInPaymentRequest;
import com.storagehub.api.credential.IssueAccessCredentialRequest;
import com.storagehub.api.checkin.MarkNoShowRequest;
import com.storagehub.api.checkin.RejectCheckInRequest;
import com.storagehub.api.checkin.ScheduleCheckInRequest;
import com.storagehub.api.checkin.SignCheckInContractRequest;
import com.storagehub.api.checkin.StartCheckInSetupRequest;
import com.storagehub.api.checkin.VerifyCheckInIdentityRequest;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.MaintenanceTaskType;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.InventoryResolutionStatus;
import com.storagehub.domain.model.User;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckInHandoverService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    @Autowired
    private AccessCredentialService accessCredentialService;
    @Autowired
    private ManualRentalPaymentService manualRentalPayments;
    @Autowired
    private PaymentProvenance paymentProvenance;
    @Autowired
    private com.storagehub.service.ledger.SandboxPaymentRecognitionPolicy sandboxPaymentPolicy;
    @Autowired(required = false)
    private PalletInventoryService palletInventory;
    @Autowired(required = false)
    private GoodsCatalogService goodsCatalogService;
    @Autowired(required = false)
    private com.storagehub.domain.repo.PaymentComplaintRepository paymentComplaintRepository;
    private final ReservationRepository reservationRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitAssignmentRepository assignmentRepository;
    private final CheckInRepository checkInRepository;
    private final FileAssetRepository fileAssetRepository;
    private final RentalRepository rentalRepository;
    private final PaymentRepository paymentRepository;
    private final MaintenanceTaskRepository maintenanceTaskRepository;
    private final ReservationGoodsItemRepository goodsItemRepository;
    private final ReservationPricingSnapshotRepository pricingSnapshotRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final FacilityScopeService facilityScopeService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public enum WorkGroup { ALL, ONGOING, PROCESSED }

    public record InventoryAlternative(UUID storageUnitId, String storageUnitCode, String unitTypeName,
        BigDecimal monthlyPrice, int requiredPallets, int availableSlots) {}

    @Transactional(readOnly = true)
    public List<InventoryAlternative> inventoryAlternatives(ActorPrincipal actor, UUID checkInId) {
        requireCheckInReader(actor);
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        CheckIn checkIn = checkInRepository.findById(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = checkIn.getReservation();
        facilityScopeService.assertCanRead(actor, reservation.getFacility().getId());
        CompleteCheckInRequest report = readHandover(checkIn);
        if (report == null || report.inventoryLines() == null || checkIn.getInventoryActualJson() == null)
            throw ApiExceptions.conflict("Vui lòng công bố kết quả kiểm kê trước khi tìm kho phù hợp.");
        if (palletInventory == null || goodsCatalogService == null)
            throw ApiExceptions.conflict("Chưa có dữ liệu để đối chiếu phương án xếp kho.");
        var originals = new HashMap<UUID, ReservationGoodsItem>();
        goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())
            .forEach(item -> originals.put(item.getId(), item));
        var items = new ArrayList<GoodsItemRequest>();
        for (var line : report.inventoryLines()) {
            if (line.actualQuantity() != null && line.actualQuantity() > 0 && originals.containsKey(line.goodsItemId()))
                items.add(actualGoodsItem(originals.get(line.goodsItemId()), line));
        }
        if (items.isEmpty()) return List.of();
        var alternatives = new ArrayList<InventoryAlternative>();
        for (StorageUnit candidate : storageUnitRepository.findByFacility_Id(reservation.getFacility().getId())) {
            if (candidate.getStatus() != StorageUnitStatus.available
                || candidate.getUnitType().getStatus() != com.storagehub.domain.model.UnitTypeStatus.active
                || (reservation.getAssignedUnit() != null && candidate.getId().equals(reservation.getAssignedUnit().getId()))
                || (candidate.getAvailableFrom() != null && candidate.getAvailableFrom().isAfter(Instant.now()))) continue;
            boolean accepted = true;
            for (int i = 0; i < items.size(); i++) {
                try { goodsCatalogService.validateForUnitType(candidate.getUnitType(), items.get(i), i); }
                catch (ApiException exception) {
                    if (exception.getStatus() != org.springframework.http.HttpStatus.BAD_REQUEST) throw exception;
                    accepted = false; break;
                }
            }
            if (!accepted) continue;
            var fit = palletInventory.evaluateActualFit(candidate, items);
            if (fit == null || fit.plan() == null || fit.layoutMissing() || fit.reviewRequired()) continue;
            alternatives.add(new InventoryAlternative(candidate.getId(), candidate.getCode(), candidate.getUnitType().getName(),
                candidate.getUnitType().getMonthlyPrice(), fit.plan().requiredPallets(), fit.plan().availableSlots()));
        }
        alternatives.sort(java.util.Comparator.comparing(InventoryAlternative::monthlyPrice,
            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).thenComparing(InventoryAlternative::storageUnitCode));
        return alternatives.stream().limit(5).toList();
    }

    private static final Duration CUSTOMER_RECEIPT_MANUAL_REMINDER_COOLDOWN = Duration.ofHours(2);
    private static final String CUSTOMER_RECEIPT_REMINDER_TITLE = "Nhắc khách xác nhận nhận kho";

    @Transactional(readOnly = true)
    public PageResponse<CheckInResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        String q,
        String status,
        WorkGroup group,
        Instant scheduledFrom,
        Instant scheduledTo,
        int page,
        int pageSize,
        String correlationId
    ) {
        requireCheckInReader(actor);
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
        Instant now = Instant.now();
        Page<CheckInResponse> result = reservationRepository.findCheckInWork(
            group.name(), now, ReservationCheckInPolicy.firstValidAppointment(now),
            now.minus(java.time.Duration.ofHours(ReservationCheckInPolicy.CUSTOMER_RECEIPT_CONFIRMATION_HOURS)),
            now.minus(java.time.Duration.ofHours(
                ReservationCheckInPolicy.CUSTOMER_RECEIPT_CONFIRMATION_HOURS
                    - ReservationCheckInPolicy.CUSTOMER_RECEIPT_REMINDER_HOURS)),
            facilityId, clean(q), statusFilter.enabled(), statusFilter.unscheduled(),
            statusFilter.checkInStatus(), scheduledFrom, scheduledTo, scoped, facilityIds,
            pageRequest(page, pageSize)
        ).map(row -> toResponse(actor, row.getReservation(), row.getCheckIn(), row.getAssignment()));
        return PageResponse.from(result, correlationId);
    }

    @Transactional
    public ReceiptReminderResponse remindCustomerReceipt(ActorPrincipal actor, UUID checkInId) {
        requireCheckInReader(actor);
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = checkIn.getReservation();
        facilityScopeService.assertCanRead(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.handover_pending_receipt) {
            throw ApiExceptions.conflict("Hồ sơ không còn chờ khách xác nhận nhận kho.");
        }
        if (checkIn.getCheckedInAt() == null) {
            throw ApiExceptions.conflict("Hồ sơ chưa có thời điểm bàn giao kho.");
        }

        Instant now = Instant.now();
        Instant dueAt = ReservationCheckInPolicy.customerReceiptConfirmationDueAt(checkIn.getCheckedInAt());
        boolean overdue = dueAt != null && !dueAt.isAfter(now);
        String dueText = dueAt == null
            ? ""
            : " Hạn xác nhận là " + dueAt.atZone(VIETNAM_ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String content = overdue
            ? "Cơ sở nhắc bạn xác nhận đã nhận kho cho đơn " + reservation.getReservationCode()
                + ". Hồ sơ đã quá hạn xác nhận, vui lòng xác nhận hoặc liên hệ cơ sở để được hỗ trợ."
            : "Cơ sở nhắc bạn xác nhận đã nhận kho cho đơn " + reservation.getReservationCode()
                + " để bắt đầu thời gian chuẩn bị." + dueText;
        RoleCode reminderRole = actor.hasRole(RoleCode.STAFF) ? RoleCode.STAFF : RoleCode.MANAGER;
        boolean sent = notificationService.createNotificationIfNotSentSince(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            CUSTOMER_RECEIPT_REMINDER_TITLE, content, reservation.getId(),
            now.minus(CUSTOMER_RECEIPT_MANUAL_REMINDER_COOLDOWN), reminderRole
        );
        if (sent) {
            auditLogService.recordMutation(
                requireActor(actor), "CUSTOMER_RECEIPT_REMINDER_SENT", "CheckIn", checkIn.getId(),
                reservation.getFacility().getId(),
                Map.of("checkInStatus", checkIn.getStatus().name()),
                Map.of("customerNotified", true, "overdue", overdue)
            );
        }
        return new ReceiptReminderResponse(sent, sent ? now : null, dueAt, overdue);
    }

    @Transactional
    public CheckInResponse releaseAfterCustomerReceiptTimeout(ActorPrincipal actor, UUID checkInId) {
        if (actor == null || !actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Chỉ Manager được giải phóng gian kho sau thời hạn xác nhận.");
        }
        requireCheckInReader(actor);
        authorizationService.require(actor, SystemPermission.MANAGE_RENTALS);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.handover_pending_receipt
            || reservation.getStatus() != ReservationStatus.AWAITING_CUSTOMER_RECEIPT) {
            throw ApiExceptions.conflict("Hồ sơ không còn chờ khách xác nhận nhận kho.");
        }
        Instant releaseAt = ReservationCheckInPolicy.customerReceiptReleaseAt(checkIn.getCheckedInAt());
        Instant now = Instant.now();
        if (releaseAt == null || now.isBefore(releaseAt)) {
            throw ApiExceptions.conflict("Chưa đủ thời hạn để giải phóng gian kho.");
        }
        if (checkIn.getRental() != null) {
            throw ApiExceptions.conflict("Hồ sơ đã có thời gian thuê, không thể giải phóng theo hạn xác nhận.");
        }

        UnitAssignment assignment = assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        ).orElseThrow(() -> ApiExceptions.conflict("Hồ sơ không còn gian kho đang được giữ."));
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        if (reservation.getAssignedUnit() == null
            || !reservation.getAssignedUnit().getId().equals(unit.getId())) {
            throw ApiExceptions.conflict("Hồ sơ và gian kho đang giữ không khớp.");
        }
        if (unit.getStatus() != StorageUnitStatus.assigned
            && unit.getStatus() != StorageUnitStatus.reserved) {
            throw ApiExceptions.conflict("Gian kho không còn ở trạng thái có thể giải phóng.");
        }

        User operator = requireActor(actor);
        String reason = "Khách hàng không xác nhận nhận kho trong 3 ngày";
        StorageUnitStatus previousUnitStatus = unit.getStatus();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.cancelled);
        checkIn.setRejectionReason(reason);
        checkIn.setRejectionDisposition(UnitReleaseDisposition.AVAILABLE);
        checkIn.setReadinessNote((checkIn.getReadinessNote() == null ? "" : checkIn.getReadinessNote() + " | ")
            + reason);

        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(operator);
        assignment.setCancelledAt(now);
        assignment.setCancelReason(reason);
        assignment.setReleaseDisposition(UnitReleaseDisposition.AVAILABLE);
        assignment.setPreviousStorageUnitStatus(previousUnitStatus);
        unit.setStatus(StorageUnitStatus.available);
        unit.setAvailableFrom(now);
        unit.setLastReleasedAt(now);

        reservation.setAssignedUnit(null);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(now);
        reservation.setArchivedAt(now);
        reservation.setCancelReason(reason);
        reservation.setCancellationType(
            com.storagehub.domain.model.ReservationCancellationType.CUSTOMER_RECEIPT_TIMEOUT
        );

        storageUnitRepository.saveAndFlush(unit);
        assignmentRepository.saveAndFlush(assignment);
        reservationRepository.saveAndFlush(reservation);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        if (accessCredentialService != null) {
            accessCredentialService.revokeForReservation(
                actor, reservation.getId(), "Thu hồi quyền ra vào do khách không xác nhận nhận kho trong 3 ngày"
            );
        }

        auditLogService.recordMutation(
            operator, "CUSTOMER_RECEIPT_TIMEOUT_RELEASED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of(
                "checkInStatus", CheckInStatus.handover_pending_receipt,
                "reservationStatus", ReservationStatus.AWAITING_CUSTOMER_RECEIPT,
                "assignmentStatus", UnitAssignmentStatus.ACTIVE,
                "storageUnitStatus", previousUnitStatus
            ),
            Map.of(
                "checkInStatus", CheckInStatus.cancelled,
                "reservationStatus", ReservationStatus.CANCELLED,
                "assignmentStatus", UnitAssignmentStatus.CANCELLED,
                "storageUnitStatus", StorageUnitStatus.available,
                "releaseAt", releaseAt,
                "reason", reason
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Gian kho đã được giải phóng",
            "Đơn " + reservation.getReservationCode()
                + " đã được đóng vì bạn chưa xác nhận nhận kho trong thời hạn 3 ngày. Vui lòng liên hệ cơ sở nếu cần được hỗ trợ.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Đã giải phóng gian kho do quá hạn xác nhận",
            "Đơn " + reservation.getReservationCode()
                + " đã được giải phóng gian kho sau khi khách không xác nhận nhận kho trong 3 ngày.",
            reservation.getId()
        );
        return toResponse(actor, reservation, saved, assignment);
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
        UnitAssignment assignment = activeAssignment(reservationId);
        StorageUnit unit = lockAssignedUnit(assignment);
        User operator = requireActor(actor);
        CheckIn checkIn = checkInRepository.findByReservation_Id(reservationId).orElseGet(CheckIn::new);
        if (checkIn.getId() != null && checkIn.getStatus() != CheckInStatus.scheduled) {
            throw ApiExceptions.conflict("Only a scheduled check-in can be rescheduled");
        }
        // The appointment belongs to the customer. Keep the request field for API
        // compatibility, but never let a staff actor move the customer appointment.
        Instant appointmentAt = effectiveAppointment(reservation, checkIn);
        if (appointmentAt == null) {
            // Legacy reservations created before appointmentAt was persisted can
            // still be scheduled once; the value is then pinned for subsequent runs.
            appointmentAt = request.scheduledAt();
        }
        requireIntakeReady(reservation, assignment, unit, appointmentAt);
        checkIn.setReservation(reservation);
        checkIn.setPerformedBy(operator);
        checkIn.setAssignedStaff(resolveAssignedStaff(
            request.assignedStaffId(), operator, reservation.getFacility().getId()
        ));
        checkIn.setStatus(CheckInStatus.scheduled);
        reservation.setAppointmentAt(appointmentAt);
        checkIn.setScheduledAt(appointmentAt);
        checkIn.setCheckedInAt(null);
        checkIn.setIdentityVerifiedAt(null);
        checkIn.setVerifiedIdentityDocument(null);
        checkIn.setIdentityVerificationNote(null);
        checkIn.setContractSignedAt(null);
        checkIn.setEmergencyContactsSnapshot(null);
        checkIn.setContractEvidenceReference(null);
        checkIn.setPaymentCollectedAt(null);
        checkIn.setSetupStartedAt(null);
        checkIn.setSetupDueAt(null);
        checkIn.setSetupCompletedAt(null);
        checkIn.setInventoryVerifiedAt(null);
        checkIn.setInventoryResolutionStatus(InventoryResolutionStatus.NOT_STARTED);
        checkIn.setInventoryActualJson(null);
        checkIn.setInventoryMismatchJson(null);
        checkIn.setInventorySuggestedQuantitiesJson(null);
        checkIn.setInventoryAcceptedQuantitiesJson(null);
        checkIn.setInventoryDecisionAt(null);
        checkIn.setInventoryDecisionNote(null);
        checkIn.setInventoryFullFit(false);
        checkIn.setInventoryPartialFitAvailable(false);
        checkIn.setInventorySuggestedPackageCount(null);
        checkIn.setInventoryMismatchSummary(null);
        checkIn.setLockReference(null);
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
                "scheduledAt", appointmentAt,
                "assignedStaffId", saved.getAssignedStaff().getId()
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
        return toResponse(actor, reservation, saved, assignment);
    }

    @Transactional
    public CheckInResponse verifyIdentity(
        ActorPrincipal actor,
        UUID checkInId,
        VerifyCheckInIdentityRequest request
    ) {
        CheckIn checkIn = requireWorkflowStep(actor, checkInId, CheckInStatus.scheduled);
        Reservation reservation = checkIn.getReservation();
        String actualIdentity = com.storagehub.validation.CccdPolicy.normalize(request.identityDocumentNumber());
        if (com.storagehub.validation.CccdPolicy.needsBirthdayConfirmation(actualIdentity) && !Boolean.TRUE.equals(request.adulthoodConfirmed()))
            throw ApiExceptions.validation("Staff cần xác nhận khách đã đủ 18 tuổi theo ngày sinh trên giấy tờ gốc.", null);
        String identityError = com.storagehub.validation.CccdPolicy.error(actualIdentity);
        if (identityError != null) throw ApiExceptions.validation(identityError, null);
        if (actualIdentity == null) {
            throw ApiExceptions.validation("identityDocumentNumber is required", null);
        }
        String declaredIdentity = normalizeIdentityDocument(reservation.getIdentityDocumentNumber());
        boolean mismatch = declaredIdentity != null && !declaredIdentity.equals(actualIdentity);
        if (mismatch || declaredIdentity == null) {
            reservation.setIdentityDocumentNumber(actualIdentity);
            reservationRepository.saveAndFlush(reservation);
        }

        User operator = requireActor(actor);
        Instant now = Instant.now();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.identity_verified);
        User customer = userRepository.findByIdForUpdate(reservation.getCustomer().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Customer not found"));
        CustomerIdentityPolicy.assign(customer, actualIdentity, userRepository);
        customer.getProfileDetails().setIdentityVerifiedAt(now);
        CustomerProfilePolicy.validate(customer, true);
        userRepository.saveAndFlush(customer);
        checkIn.setIdentityVerifiedAt(now);
        checkIn.setVerifiedIdentityDocument(actualIdentity);
        String note = clean(request.note());
        if (mismatch) {
            String auditNote = "[Đã điều chỉnh CCCD theo giấy tờ gốc]";
            note = note == null ? auditNote : note + " " + auditNote;
        }
        checkIn.setIdentityVerificationNote(note);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        recordWorkflowAudit(operator, reservation, saved, "CHECKIN_IDENTITY_VERIFIED",
            CheckInStatus.scheduled, CheckInStatus.identity_verified);
        return toResponse(actor, reservation, saved, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse correctIdentity(ActorPrincipal actor, UUID checkInId, VerifyCheckInIdentityRequest request) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getIdentityVerifiedAt() == null) throw ApiExceptions.conflict("Vui lòng thực hiện bước đối chiếu CCCD trước.");
        if (request.note() == null || request.note().isBlank()) throw ApiExceptions.validation("Cần lý do sửa CCCD và xác nhận đã đối chiếu lại giấy tờ gốc.", null);
        User customer = userRepository.findByIdForUpdate(reservation.getCustomer().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Customer not found"));
        if (com.storagehub.validation.CccdPolicy.needsBirthdayConfirmation(request.identityDocumentNumber()) && !Boolean.TRUE.equals(request.adulthoodConfirmed()))
            throw ApiExceptions.validation("Staff cần xác nhận khách đã đủ 18 tuổi theo ngày sinh trên giấy tờ gốc.", null);
        CustomerIdentityPolicy.assign(customer, request.identityDocumentNumber(), userRepository);
        customer.getProfileDetails().setIdentityVerifiedAt(Instant.now());
        userRepository.saveAndFlush(customer);
        String value = customer.getProfileDetails().getIdentityDocumentNumber();
        checkIn.setVerifiedIdentityDocument(value);
        checkIn.setIdentityVerificationNote(request.note().replaceAll("[0-9]{12}", "[CCCD đã ẩn]"));
        reservation.setIdentityDocumentNumber(value);
        reservationRepository.saveAndFlush(reservation);
        checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(requireActor(actor), "CUSTOMER_IDENTITY_CORRECTED", "User", customer.getId(),
            reservation.getFacility().getId(), Map.of("identityVerified", true), Map.of("identityCorrected", true, "reason", checkIn.getIdentityVerificationNote()));
        return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse signContract(
        ActorPrincipal actor,
        UUID checkInId,
        SignCheckInContractRequest request
    ) {
        CheckIn checkIn = requireWorkflowStep(actor, checkInId, CheckInStatus.identity_verified);
        validateWorkflowEvidence(checkInId, request.contractEvidenceFileId(), "Contract evidence");
        Reservation reservation = checkIn.getReservation();
        var pricing = pricingSnapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu snapshot điều khoản đã chấp nhận."));
        if (pricing.getSetupEarlyExitPolicyVersion() != null
            && !pricing.getSetupEarlyExitPolicyVersion().equals(request.confirmedSetupEarlyExitPolicyVersion()))
            throw ApiExceptions.validation("Nhân viên cần xác nhận hợp đồng đã ký có điều khoản phí trả kho trong thời gian chuẩn bị đúng báo giá.", null);
        User operator = requireActor(actor);
        checkIn.setPerformedBy(operator);
        CustomerProfilePolicy.validate(reservation.getCustomer(), true);
        if (!java.util.Objects.equals(checkIn.getVerifiedIdentityDocument(), reservation.getCustomer().getProfileDetails().getIdentityDocumentNumber()))
            throw ApiExceptions.conflict("CCCD trong hồ sơ phải khớp giấy tờ đã được nhân viên đối chiếu trước khi ký hợp đồng.");
        checkIn.setEmergencyContactsSnapshot(CustomerProfilePolicy.snapshot(reservation.getCustomer()));
        checkIn.setStatus(CheckInStatus.contract_signed);
        checkIn.setContractSignedAt(Instant.now());
        checkIn.setContractEvidenceReference(request.contractEvidenceFileId().toString());
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        recordWorkflowAudit(operator, reservation, saved, "CHECKIN_CONTRACT_SIGNED",
            CheckInStatus.identity_verified, CheckInStatus.contract_signed);
        return toResponse(actor, reservation, saved, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse confirmNoRemainingPayment(
        ActorPrincipal actor,
        UUID checkInId,
        ConfirmCheckInPaymentRequest request
    ) {
        CheckIn checkIn = requireWorkflowStep(actor, checkInId, CheckInStatus.contract_signed);
        Reservation reservation = checkIn.getReservation();
        CustomerProfilePolicy.validate(reservation.getCustomer(), true);
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        BigDecimal due = snapshot.getDueAtCheckIn();
        if (due == null || due.signum() > 0) {
            throw ApiExceptions.conflict("The remaining check-in payment must be recorded before the preparation period starts");
        }
        User operator = requireActor(actor);
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.payment_collected);
        checkIn.setPaymentCollectedAt(Instant.now());
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        recordWorkflowAudit(operator, reservation, saved, "CHECKIN_PAYMENT_CONFIRMED",
            CheckInStatus.contract_signed, CheckInStatus.payment_collected);
        return toResponse(actor, reservation, saved, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse startSetup(
        ActorPrincipal actor,
        UUID checkInId,
        StartCheckInSetupRequest request
    ) {
        CheckIn checkIn = requireWorkflowStep(actor, checkInId, CheckInStatus.payment_collected);
        Reservation reservation = checkIn.getReservation();
        requireRemainingPayment(reservation);
        String lockReference = request.lockReference().trim();
        validateNoPasswordReference(lockReference);
        if (request.initialUnitCondition() == null || request.initialUnitCondition().isBlank()) {
            throw ApiExceptions.validation("Cần ghi nhận tình trạng kho trống trước khi bàn giao.", null);
        }
        if (request.evidenceReferences() == null || request.evidenceReferences().isEmpty()) {
            throw ApiExceptions.validation("Cần ít nhất một ảnh hoặc PDF bằng chứng kho trống trước khi bàn giao.", null);
        }
        validateEvidence(checkInId, request.evidenceReferences());
        User operator = requireActor(actor);
        Instant now = Instant.now();
        // This endpoint records only the physical handover. The paid rental and
        // free preparation period start after the customer confirms receipt.
        if (accessCredentialService != null) {
            accessCredentialService.issue(
                actor, checkInId,
                new IssueAccessCredentialRequest(
                    com.storagehub.domain.model.AccessCredentialType.LOCK,
                    lockReference,
                    "Bàn giao ổ khóa để khách hàng chuẩn bị hàng"
                )
            );
        }
        reservation.getAssignedUnit().setStatus(StorageUnitStatus.assigned);
        storageUnitRepository.saveAndFlush(reservation.getAssignedUnit());
        ReservationStatus previousReservationStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        reservationRepository.saveAndFlush(reservation);
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.handover_pending_receipt);
        checkIn.setSetupStartedAt(null);
        checkIn.setSetupDueAt(null);
        checkIn.setSetupCompletedAt(null);
        checkIn.setInventoryResolutionStatus(InventoryResolutionStatus.NOT_STARTED);
        checkIn.setInventoryActualJson(null);
        checkIn.setInventoryMismatchJson(null);
        checkIn.setInventorySuggestedQuantitiesJson(null);
        checkIn.setInventoryAcceptedQuantitiesJson(null);
        checkIn.setInventoryDecisionAt(null);
        checkIn.setInventoryDecisionNote(null);
        checkIn.setInventoryFullFit(false);
        checkIn.setInventoryPartialFitAvailable(false);
        checkIn.setInventorySuggestedPackageCount(null);
        checkIn.setInventoryMismatchSummary(null);
        checkIn.setCheckedInAt(now);
        checkIn.setRental(null);
        checkIn.setLockReference(lockReference);
        checkIn.setReadinessNote("Tình trạng kho trống: " + request.initialUnitCondition().trim()
            + " | Bằng chứng: " + String.join(",", request.evidenceReferences()));
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        recordWorkflowAudit(operator, reservation, saved, "CHECKIN_HANDOVER_RECORDED",
            CheckInStatus.payment_collected, CheckInStatus.handover_pending_receipt);
        auditLogService.recordMutation(
            operator, "CHECKIN_HANDOVER_WAITING_CUSTOMER_RECEIPT", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of("reservationStatus", previousReservationStatus),
            Map.of("reservationStatus", ReservationStatus.AWAITING_CUSTOMER_RECEIPT)
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã bàn giao kho, chờ bạn xác nhận",
            "Gian kho " + reservation.getAssignedUnit().getCode()
                + " đã được bàn giao. Mã ổ khóa đã bàn giao: " + lockReference
                + ". Vui lòng kiểm tra mã và xác nhận đã nhận kho trên tài khoản khách hàng để bắt đầu thời gian chuẩn bị.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Đã bàn giao kho, chờ khách xác nhận",
            "Đơn " + reservation.getReservationCode()
                + " đã bàn giao kho trống và đang chờ khách xác nhận nhận kho.",
            reservation.getId()
        );
        return toResponse(actor, reservation, saved, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse completeSetup(
        ActorPrincipal actor,
        UUID checkInId,
        CompleteCheckInSetupRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findById(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = checkIn.getReservation();
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.setup_completed && checkIn.getStatus() != CheckInStatus.completed) {
            throw ApiExceptions.conflict("Khách hàng cần báo đã chuẩn bị hàng xong trên cổng khách hàng.");
        }
        return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse recordInventoryArrival(ActorPrincipal actor, UUID checkInId) {
        CheckIn checkIn = requireOpenInventoryVisit(actor, checkInId);
        Reservation reservation = checkIn.getReservation();
        if (checkIn.getInventoryArrivedAt() == null) {
            Instant arrivedAt = Instant.now();
            checkIn.setInventoryArrivedAt(arrivedAt);
            checkInRepository.saveAndFlush(checkIn);
            auditLogService.recordMutation(requireActor(actor), "INVENTORY_CUSTOMER_ARRIVED", "CheckIn", checkInId,
                reservation.getFacility().getId(), null, Map.of("arrivedAt", arrivedAt,
                    "appointmentAt", checkIn.getInventoryAppointmentAt()));
        }
        return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
    }

    @Transactional
    public CheckInResponse verifyInventoryIdentity(ActorPrincipal actor, UUID checkInId,
        com.storagehub.api.checkin.VerifyInventoryIdentityRequest request) {
        CheckIn checkIn = requireOpenInventoryVisit(actor, checkInId);
        Reservation reservation = checkIn.getReservation();
        if (checkIn.getInventoryArrivedAt() == null || !Boolean.TRUE.equals(request.bookingCustomerPresent())) {
            throw ApiExceptions.conflict("Cần ghi nhận đúng người đặt kho đã trực tiếp đến kiểm kê.");
        }
        validateIdentityDocument(reservation, request.identityDocumentNumber());
        FileAsset photo = requireInventoryIdentityPhoto(checkIn, request.photoId());
        if (!photo.getUploadedBy().getId().equals(actor.userId())) {
            throw ApiExceptions.validation("Vui lòng tải ảnh khách tại buổi kiểm kê từ tài khoản của bạn.", null);
        }
        if (request.photoId().equals(checkIn.getInventoryIdentityPhotoId()) && checkIn.getInventoryIdentityVerifiedAt() != null) {
            return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
        }
        User verifier = requireActor(actor);
        Instant verifiedAt = Instant.now();
        UUID previousPhoto = checkIn.getInventoryIdentityPhotoId();
        checkIn.setInventoryIdentityVerifiedAt(verifiedAt);
        checkIn.setInventoryIdentityVerifiedBy(verifier);
        checkIn.setInventoryIdentityPhotoId(photo.getId());
        checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(verifier, "INVENTORY_IDENTITY_VERIFIED", "CheckIn", checkInId,
            reservation.getFacility().getId(), previousPhoto == null ? null : Map.of("photoId", previousPhoto),
            Map.of("verifiedAt", verifiedAt, "photoId", photo.getId()));
        return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
    }

    private CheckIn requireOpenInventoryVisit(ActorPrincipal actor, UUID checkInId) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ kiểm kê."));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy đơn đặt kho."));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu hồ sơ thuê để kiểm kê."));
        if (reservation.getStatus() != ReservationStatus.PREPARING || checkIn.getStatus() != CheckInStatus.setup_completed
            || checkIn.getInventoryResolutionStatus() != InventoryResolutionStatus.NOT_STARTED
            || !rental.isInventoryPending() || checkIn.getInventoryAppointmentAt() == null) {
            throw ApiExceptions.conflict("Hồ sơ cần có lịch hẹn và đang chờ kiểm kê.");
        }
        InventoryDeadlinePolicy.requireInventoryOpen(rental, Instant.now());
        return checkIn;
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
            return toResponse(actor, reservation, checkIn, latestAssignment(reservation.getId()));
        }
        Instant now = Instant.now();
        if (checkIn.getStatus() != CheckInStatus.setup_completed) {
            throw ApiExceptions.conflict("Khách hàng phải báo đã chuẩn bị hàng xong trước khi kiểm kê hàng hóa");
        }
        if (checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.PENDING_CUSTOMER
            || checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.PARTIAL_ACCEPTED
            || checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.RETURN_REQUESTED) {
            throw ApiExceptions.conflict("Kết quả kiểm kê đã được công bố và đang chờ xử lý.");
        }
        if (!isCheckInReadyStatus(reservation.getStatus())) {
            throw ApiExceptions.conflict("Reservation is not ready for inventory check");
        }
        requirePaidReservationDeposit(reservation);
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu mốc thời gian chuẩn bị của hồ sơ thuê"));
        InventoryDeadlinePolicy.requireInventoryOpen(rental, now);
        if (rental.getSetupEndsAt() == null) {
            throw ApiExceptions.conflict("Thiếu mốc thời gian chuẩn bị của hồ sơ thuê");
        }
        if ((rental.getSetupCompletionDeadlineAt() != null || rental.getInventoryAppointmentAt() != null)
            && (rental.getInventoryArrivedAt() == null || rental.getInventoryIdentityVerifiedAt() == null))
            throw ApiExceptions.conflict("Vui lòng ghi nhận khách đến và xác minh chính chủ bằng ảnh trước khi kiểm kê.");
        if (rental.getStatus() == RentalStatus.setup && rental.getStartDate() != null
            && !rental.getStartDate().isAfter(now.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate())) {
            rental.setStatus(RentalStatus.active);
        }
        if (rental.getStatus() != RentalStatus.setup && rental.getStatus() != RentalStatus.active) {
            throw ApiExceptions.conflict("Hồ sơ thuê chưa sẵn sàng để kiểm kê.");
        }
        if (!request.checklist().identityVerified()) {
            throw ApiExceptions.conflict("Customer identity verification is required");
        }
        if (checkIn.getInventoryAppointmentAt() == null || checkIn.getInventoryArrivedAt() == null
            || checkIn.getInventoryIdentityVerifiedAt() == null || checkIn.getInventoryIdentityVerifiedBy() == null) {
            throw ApiExceptions.conflict("Cần ghi nhận khách đến và xác minh đúng người đặt kho trước khi hoàn tất kiểm kê.");
        }
        requireInventoryIdentityPhoto(checkIn, checkIn.getInventoryIdentityPhotoId());
        UnitAssignment assignment = activeAssignment(reservation.getId());
        StorageUnit unit = storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
        assertReservationUnitMatch(reservation, assignment, unit);
        if (unit.getStatus() != StorageUnitStatus.reserved && unit.getStatus() != StorageUnitStatus.assigned) {
            throw ApiExceptions.conflict("Storage unit is not assigned to this handover");
        }
        validateIdentityDocument(reservation, request.identityDocumentNumber());
        var goodsItems = goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId());
        boolean resolutionEnabled = request.inventoryLines() != null && !goodsItems.isEmpty();
        boolean strictInventory = rental.getSetupCompletionDeadlineAt() != null || rental.getInventoryAppointmentAt() != null;
        if (strictInventory && !goodsItems.isEmpty() && !resolutionEnabled)
            throw ApiExceptions.validation("Vui lòng kiểm kê đủ từng dòng hàng thực tế.", null);
        boolean catalogRejected = resolutionEnabled && validateActualGoods(unit, goodsItems, request, strictInventory);
        boolean weightExceedsCapacity = request.actualMeasurements().weightKg()
            .compareTo(unit.getUnitType().getMaxLoadKg()) > 0;
        boolean volumeExceedsCapacity = request.actualMeasurements().actualVolumeM3()
            .compareTo(unit.getUnitType().getVolumeM3()) > 0;
        boolean dimensionsExceedCapacity = !dimensionsFit(request, unit);
        boolean aggregateCapacityMismatch = weightExceedsCapacity
            || volumeExceedsCapacity
            || dimensionsExceedCapacity;
        if (!resolutionEnabled && aggregateCapacityMismatch) {
            if (weightExceedsCapacity) {
                throw ApiExceptions.conflict("Actual goods weight exceeds the assigned storage unit capacity");
            }
            if (volumeExceedsCapacity) {
                throw ApiExceptions.conflict("Actual goods volume exceeds the assigned storage unit capacity");
            }
            throw ApiExceptions.conflict("Actual goods dimensions exceed the assigned storage unit capacity");
        }
        validateGoodsDeclaration(goodsItems, request);
        validateVariance(reservation, request, goodsItems);
        validateInventoryLines(goodsItems, request);
        validateEvidence(checkInId, request.evidenceReferences());
        validateNoPasswordHandover(request);
        requireRemainingPayment(reservation);
        if (checkIn.getLockReference() == null
            || !checkIn.getLockReference().equals(request.lockReference().trim())) {
            throw ApiExceptions.conflict("Mã ổ khóa không khớp với mã đã bàn giao");
        }

        User operator = requireActor(actor);
        InventoryFitEvaluation fit = catalogRejected
            ? new InventoryFitEvaluation(false, false, 0, "Loại hàng hoặc chất liệu thực tế không được tiếp nhận tại gian kho này", Map.of()) : resolutionEnabled
            ? evaluateInventoryFit(reservation, unit, goodsItems, request, strictInventory)
            : new InventoryFitEvaluation(!aggregateCapacityMismatch, false, request.packageCount(),
                aggregateCapacityMismatch ? "Sức chứa, kích thước hoặc khối lượng thực tế vượt giới hạn gian kho" : null,
                Map.of());
        boolean declaredVariance = hasMeasurementVariance(reservation, request, goodsItems)
            || hasGoodsDeclarationVariance(goodsItems, request) || actualLineVariance(goodsItems, request);
        if (declaredVariance && request.inventoryLines() != null && request.inventoryLines().stream().anyMatch(l -> l.actualGoods() != null)
            && (request.varianceReason() == null || request.varianceReason().isBlank()))
            throw ApiExceptions.validation("Vui lòng ghi rõ sai lệch loại hàng, chất liệu hoặc điều kiện xếp thực tế.", null);
        boolean conditionVariance = request.inventoryLines() != null
            && request.inventoryLines().stream().anyMatch(line -> line.condition()
                == CompleteCheckInRequest.InventoryLine.Condition.DAMAGED);
        boolean inventoryMismatch = resolutionEnabled
            && (declaredVariance || conditionVariance || packagingVariance(reservation, request) || !fit.fullFit());
        if ((request.actualPackagingMethod() != null || request.packagingCondition() != null)
            && (request.actualPackagingMethod() == null || request.actualPackagingMethod().isBlank() || request.packagingCondition() == null))
            throw ApiExceptions.validation("Cần ghi nhận đủ phương thức và tình trạng đóng gói thực tế.", null);
        if (packagingVariance(reservation, request) && (request.varianceReason() == null || request.varianceReason().isBlank()))
            throw ApiExceptions.validation("Cần ghi rõ sai lệch phương thức hoặc tình trạng đóng gói.", null);

        checkIn.setInventoryFullFit(fit.fullFit());
        checkIn.setInventoryPartialFitAvailable(fit.partialFitAvailable());
        checkIn.setInventorySuggestedPackageCount(fit.suggestedPackageCount());
        checkIn.setInventoryMismatchSummary(fit.summary());
        checkIn.setInventoryActualJson(writeInventoryActual(request, fit));
        checkIn.setInventoryMismatchJson(inventoryMismatch
            ? writeInventoryMismatch(reservation, goodsItems, request, fit)
            : null);
        checkIn.setInventorySuggestedQuantitiesJson(writeSuggestedQuantities(fit.suggestedQuantities()));
        checkIn.setChecklistJson(writeHandover(request));
        if (inventoryMismatch || rental.getSetupCompletionDeadlineAt() != null || rental.getInventoryAppointmentAt() != null) {
            rental.setInventoryResultPublishedAt(now);
            String removalNotice = "";
            if (!fit.fullFit() && !fit.partialFitAvailable()
                && rental.getInventoryRemovalPublishedAt() == null) {
                rental.setInventoryRemovalPublishedAt(now);
                rental.setInventoryRemovalDeadlineAt(InventoryTimelinePolicy.removalDeadline(now));
            }
            if (rental.getInventoryRemovalDeadlineAt() != null) {
                removalNotice = " Hạn đưa hàng ra khi chọn trả kho: "
                    + rental.getInventoryRemovalDeadlineAt().atZone(VIETNAM_ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")) + ".";
            }
            checkIn.setPerformedBy(operator);
            checkIn.setInventoryResolutionStatus(InventoryResolutionStatus.PENDING_CUSTOMER);
            checkIn.setInventoryDecisionAt(null);
            checkIn.setInventoryDecisionNote(null);
            CheckIn saved = checkInRepository.saveAndFlush(checkIn);
            rentalRepository.saveAndFlush(rental);
            notificationService.createNotification(
                reservation.getCustomer().getId(), NotificationType.CHECKIN,
                "Cần bạn xác nhận kết quả kiểm kê",
                "Hàng hóa thực tế của đơn " + reservation.getReservationCode()
                    + " có điểm khác với khai báo. Quý khách vui lòng xem kết quả kiểm kê và chọn cách xử lý hàng hóa hoặc yêu cầu trả kho." + removalNotice,
                reservation.getId()
            );
            notificationService.createFacilityOperationsNotifications(
                reservation.getFacility().getId(), NotificationType.CHECKIN,
                "Kiểm kê có sai lệch, chờ khách xác nhận",
                "Đơn " + reservation.getReservationCode()
                    + " đã ghi nhận sai lệch hàng hóa. Hệ thống đã tính phương án xếp và đang chờ khách chọn hướng xử lý.",
                reservation.getId()
            );
            return toResponse(actor, reservation, saved, assignment);
        }

        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.completed);
        checkIn.setInventoryVerifiedAt(now);
        checkIn.setInventoryResolutionStatus(InventoryResolutionStatus.ACCEPTED);
        rental.setInventoryVerifiedAt(now);
        rental.setInventoryPending(false);
        rentalRepository.saveAndFlush(rental);
        completeInventoryTask(rental, now, request.notes());
        assignment.setStatus(UnitAssignmentStatus.COMPLETED);
        ReservationStatus previousReservationStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.COMPLETED);
        unit.setStatus(StorageUnitStatus.occupied);

        storageUnitRepository.saveAndFlush(unit);
        reservationRepository.saveAndFlush(reservation);
        assignmentRepository.saveAndFlush(assignment);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);
        auditLogService.recordMutation(
            operator, "CHECKIN_HANDOVER_COMPLETED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of(
                "checkInStatus", CheckInStatus.setup_completed,
                "reservationStatus", previousReservationStatus,
                "assignmentStatus", UnitAssignmentStatus.ACTIVE,
                "storageUnitStatus", StorageUnitStatus.reserved
            ),
            Map.of(
                "checkInStatus", CheckInStatus.completed,
                "reservationStatus", ReservationStatus.COMPLETED,
                "assignmentStatus", UnitAssignmentStatus.COMPLETED,
                "storageUnitStatus", StorageUnitStatus.occupied
            )
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã hoàn tất kiểm kê hàng hóa",
            "Gian kho " + unit.getCode() + " của đơn " + reservation.getReservationCode()
                + " đã được nhân viên kiểm kê sau khi bạn báo chuẩn bị hàng xong."
                + (rental.getStatus() == RentalStatus.setup
                    ? " Kỳ thuê vẫn bắt đầu theo lịch từ "
                        + rental.getStartDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        + " sau thời gian chuẩn bị miễn phí; hoàn tất sớm không làm đổi mốc này."
                    : ""),
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Đã hoàn tất kiểm kê hàng hóa",
            "Đơn " + reservation.getReservationCode()
                + " đã hoàn tất kiểm kê hàng hóa đầu vào.",
            reservation.getId()
        );
        return toResponse(actor, reservation, saved, assignment);
    }

    @Transactional
    public CheckInResponse confirmPartialInventoryRemoval(
        ActorPrincipal actor,
        UUID checkInId,
        ConfirmInventoryPartialRemovalRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        if (request == null || !Boolean.TRUE.equals(request.remainderRemoved())) {
            throw ApiExceptions.conflict("Cần xác nhận phần hàng không được tiếp nhận đã được đưa khỏi gian kho.");
        }
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.setup_completed
            || checkIn.getInventoryResolutionStatus() != InventoryResolutionStatus.PARTIAL_ACCEPTED) {
            throw ApiExceptions.conflict("Hồ sơ chưa ở bước chờ xác nhận phần hàng dư đã được đưa ra.");
        }
        validateEvidence(checkInId, request.evidenceReferences());

        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Thiếu hồ sơ thuê của thời gian chuẩn bị."));
        UnitAssignment assignment = assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        ).orElseThrow(() -> ApiExceptions.conflict("Hồ sơ phân gian kho không còn hiệu lực."));
        User operator = requireActor(actor);
        Instant now = Instant.now();

        InventoryDeadlinePolicy.requireInventoryOpen(rental, now);
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.completed);
        checkIn.setCheckedInAt(checkIn.getCheckedInAt() == null ? now : checkIn.getCheckedInAt());
        checkIn.setInventoryVerifiedAt(now);
        checkIn.setInventoryRemainderRemovedAt(now);
        checkIn.setInventoryRemainderRemovalNote(request.notes().trim());
        try {
            checkIn.setInventoryRemainderEvidenceJson(objectMapper.writeValueAsString(request.evidenceReferences()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize remainder removal evidence", exception);
        }
        rental.setInventoryVerifiedAt(now);
        rental.setInventoryPending(false);
        completeInventoryTask(rental, now, "Đã xác nhận phần hàng không được tiếp nhận đã được đưa khỏi gian kho. " + request.notes().trim());
        assignment.setStatus(UnitAssignmentStatus.COMPLETED);
        reservation.setStatus(ReservationStatus.COMPLETED);
        assignment.getStorageUnit().setStatus(StorageUnitStatus.occupied);

        rentalRepository.saveAndFlush(rental);
        storageUnitRepository.saveAndFlush(assignment.getStorageUnit());
        assignmentRepository.saveAndFlush(assignment);
        reservationRepository.saveAndFlush(reservation);
        CheckIn saved = checkInRepository.saveAndFlush(checkIn);

        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.CHECKIN,
            "Đã hoàn tất xử lý phần hàng không phù hợp",
            "Nhân viên đã xác nhận phần hàng không được tiếp nhận đã được đưa khỏi gian kho. Hồ sơ thuê đã hoàn tất kiểm kê.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Đã hoàn tất xử lý hàng không phù hợp",
            "Đơn " + reservation.getReservationCode() + " đã hoàn tất bước xác nhận phần hàng dư được đưa khỏi gian kho.",
            reservation.getId()
        );
        auditLogService.recordMutation(
            operator, "CHECKIN_PARTIAL_INVENTORY_REMOVAL_CONFIRMED", "CheckIn", saved.getId(),
            reservation.getFacility().getId(),
            Map.of("inventoryResolutionStatus", InventoryResolutionStatus.PARTIAL_ACCEPTED),
            Map.of("inventoryResolutionStatus", InventoryResolutionStatus.PARTIAL_ACCEPTED,
                "inventoryRemainderRemovedAt", now,
                "reservationStatus", ReservationStatus.COMPLETED)
        );
        return toResponse(actor, reservation, saved, assignment);
    }

    @Transactional
    public CheckInPaymentResponse recordRemainingPayment(
        ActorPrincipal actor,
        UUID checkInId,
        CheckInPaymentRequest request
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        authorizationService.require(actor, SystemPermission.MANAGE_PAYMENTS);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.contract_signed
            || !isCheckInReadyStatus(reservation.getStatus())) {
            throw ApiExceptions.conflict("Check-in is not ready to record the remaining payment");
        }
        requireLockedIntakeReady(reservation, checkIn);
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId())
            .orElseThrow(() -> ApiExceptions.conflict("Reservation pricing snapshot is missing"));
        ReservationSnapshotView.requireSetupDays(reservation,snapshot);
        if (request.amount().compareTo(snapshot.getDueAtCheckIn()) != 0) {
            throw ApiExceptions.validation("Payment amount must match the remaining check-in amount", Map.of("expectedAmount", snapshot.getDueAtCheckIn()));
        }
        User operator = requireActor(actor);
        validatePaymentEvidence(checkInId, request.paymentEvidenceFileId());
        Instant now = Instant.now();
        String reference = request.transactionReference() == null || request.transactionReference().isBlank()
            ? "EVIDENCE-" + request.paymentEvidenceFileId()
            : request.transactionReference().trim();
        var recorded = manualRentalPayments.booking(reservation,checkInId,operator,snapshot.getDueAtCheckIn(),request.amount(),
            request.paymentMethod(),reference,request.paymentEvidenceFileId(),now);
        Payment saved=recorded.payment();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.payment_collected);
        checkIn.setPaymentCollectedAt(now);
        checkInRepository.saveAndFlush(checkIn);
        if(!recorded.created())return toPaymentResponse(saved);
        auditLogService.recordMutation(
            operator, "CHECKIN_REMAINING_PAYMENT_RECORDED", "Payment", saved.getId(),
            reservation.getFacility().getId(), null, toPaymentResponse(saved)
        );
        notificationService.createNotification(
            reservation.getCustomer().getId(), NotificationType.PAYMENT,
            "Đã ghi nhận thanh toán khi nhận kho",
            "Đơn " + reservation.getReservationCode() + " đã có biên lai thanh toán phần còn lại.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.PAYMENT,
            "Đã ghi nhận thanh toán khi nhận kho",
            "Đơn " + reservation.getReservationCode() + " đã có biên lai thanh toán phần còn lại.",
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
        Instant appointmentAt = effectiveAppointment(reservation, checkIn);
        if (appointmentAt == null || appointmentAt.isAfter(Instant.now())) {
            throw ApiExceptions.conflict("Chỉ có thể ghi nhận khách vắng từ giờ hẹn nhận kho.");
        }
        UnitAssignment assignment = requireLockedIntakeReady(reservation, checkIn);
        User operator = requireActor(actor);
        Instant now = Instant.now();
        String reason = request.reason().trim();
        checkIn.setPerformedBy(operator);
        checkIn.setStatus(CheckInStatus.no_show);
        checkIn.setReadinessNote("NO_SHOW: " + reason);

        StorageUnit unit = assignment.getStorageUnit();
        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(operator);
        assignment.setCancelledAt(now);
        assignment.setCancelReason("NO_SHOW: " + reason);
        assignment.setReleaseDisposition(UnitReleaseDisposition.AVAILABLE);
        assignment.setPreviousStorageUnitStatus(unit.getStatus());
        unit.setStatus(StorageUnitStatus.available);
        unit.setAvailableFrom(now);
        unit.setLastReleasedAt(now);
        storageUnitRepository.saveAndFlush(unit);
        assignmentRepository.saveAndFlush(assignment);
        reservation.setAssignedUnit(null);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(now);
        reservation.setArchivedAt(now);
        reservation.setCancelReason("NO_SHOW: " + reason);
        reservationRepository.saveAndFlush(reservation);
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
            "Đơn " + reservation.getReservationCode() + " được ghi nhận là khách hàng không đến nhận kho theo lịch hẹn.",
            reservation.getId()
        );
        notificationService.createFacilityOperationsNotifications(
            reservation.getFacility().getId(), NotificationType.CHECKIN,
            "Khách hàng không đến nhận kho",
            "Đơn " + reservation.getReservationCode() + " được ghi nhận là khách hàng không đến nhận kho theo lịch hẹn.",
            reservation.getId()
        );
        return toResponse(actor, reservation, saved, latestAssignment(reservation.getId()));
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
        UnitAssignment assignment = requireLockedIntakeReady(reservation, checkIn);
        StorageUnit unit = assignment.getStorageUnit();
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
        return toResponse(actor, reservation, saved, assignment);
    }

    @Transactional
    public void archiveCompleted(ActorPrincipal actor, UUID checkInId) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.VIEW_CHECKINS);
        CheckIn checkIn = checkInRepository.findById(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("CheckIn was not found"));
        Reservation reservation = checkIn.getReservation();
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (checkIn.getStatus() != CheckInStatus.completed && reservation.getStatus() != ReservationStatus.COMPLETED) {
            throw ApiExceptions.conflict("Chỉ có thể xóa/lưu trữ đơn sau khi bàn giao thành công");
        }
        Instant now = Instant.now();
        if (reservation.getArchivedAt() == null) {
            reservation.setArchivedAt(now);
        }
        reservationRepository.saveAndFlush(reservation);
        User operator = userRepository.findById(actor.userId()).orElse(null);
        auditLogService.recordMutation(
            operator, "CHECKIN_ORDER_DELETED", "Reservation", reservation.getId(),
            reservation.getFacility().getId(),
            Map.of("status", reservation.getStatus().name()),
            Map.of(
                "action", "SOFT_DELETE",
                "checkInId", checkIn.getId().toString(),
                "reservationCode", reservation.getReservationCode(),
                "reason", "Nhân viên xóa đơn sau khi bàn giao thành công (Soft Delete)"
            )
        );
    }

    private CheckIn requireWorkflowStep(
        ActorPrincipal actor,
        UUID checkInId,
        CheckInStatus expectedStatus
    ) {
        requireOperator(actor);
        authorizationService.require(actor, SystemPermission.PERFORM_CHECKIN);
        CheckIn checkIn = checkInRepository.findByIdForUpdate(checkInId)
            .orElseThrow(() -> ApiExceptions.notFound("Check-in was not found"));
        Reservation reservation = reservationRepository.findByIdForUpdate(checkIn.getReservation().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Reservation was not found"));
        facilityScopeService.assertCanOperate(actor, reservation.getFacility().getId());
        if (!isCheckInReadyStatus(reservation.getStatus())) {
            throw ApiExceptions.conflict("Reservation is not READY_FOR_CHECKIN");
        }
        Instant deadline = checkInDeadline(reservation);
        if (deadline != null && Instant.now().isAfter(deadline)) {
            throw ApiExceptions.conflict("Check-in window has expired; the reservation can no longer be handed over");
        }
        if (checkIn.getStatus() != expectedStatus) {
            throw ApiExceptions.conflict(
                "Check-in step is out of order. Expected status: " + expectedStatus
            );
        }
        if (expectedStatus != CheckInStatus.setup_in_progress) {
            requireLockedIntakeReady(reservation, checkIn);
        } else {
            requirePaidReservationDeposit(reservation);
        }
        checkIn.setReservation(reservation);
        return checkIn;
    }

    private void requireHandoverWithinDeadline(Instant appointmentAt) {
        if (ReservationCheckInPolicy.handoverExpired(appointmentAt, Instant.now())) {
            throw ApiExceptions.conflict("Đã hết hạn nhận kho. Không thể tiếp tục bàn giao.");
        }
    }

    /** The booking prepayment is distinct from the security deposit already included in its quote. */
    private void requirePaidReservationDeposit(Reservation reservation) {
        if (paymentRepository == null || pricingSnapshotRepository == null || paymentProvenance == null
            || sandboxPaymentPolicy == null) {
            throw ApiExceptions.conflict("Không thể xác minh khoản trả trước của đơn đặt kho.");
        }
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        if (!ReservationSnapshotView.pricingComplete(reservation, snapshot)
            || snapshot.getReservationDepositAmount() == null
            || snapshot.getReservationDepositAmount().signum() <= 0) {
            throw ApiExceptions.conflict(ReservationSnapshotView.MISSING+": khoản trả trước khi đặt kho");
        }
        boolean paid = paymentRepository.findAllByReservation_IdAndStatusIn(
            reservation.getId(), List.of(PaymentStatus.PAID)
        ).stream().anyMatch(payment -> {
            if (payment.getPurpose() != PaymentType.RESERVATION_DEPOSIT
                || payment.getReservation() == null
                || !reservation.getId().equals(payment.getReservation().getId())
                || payment.getAmount() == null
                || payment.getAmount().compareTo(snapshot.getReservationDepositAmount()) != 0
                || payment.getRefundedAmount() == null
                || payment.getRefundedAmount().signum() != 0) return false;
            var proof = paymentProvenance.classify(payment);
            if (proof == PaymentProvenance.Classification.VERIFIED
                || proof == PaymentProvenance.Classification.SIMULATED
                || (proof == PaymentProvenance.Classification.SANDBOX
                    && sandboxPaymentPolicy.evaluate(payment, proof).eligibleForReconciliation())) {
                return true;
            }
            if (paymentComplaintRepository != null && proof == PaymentProvenance.Classification.UNVERIFIED) {
                var complaint = paymentComplaintRepository.findByReservation_Id(reservation.getId()).orElse(null);
                return complaint != null
                    && complaint.getStatus() == com.storagehub.domain.model.PaymentComplaintStatus.APPROVED
                    && complaint.getPayment() != null
                    && payment.getId().equals(complaint.getPayment().getId())
                    && complaint.getReviewedBy() != null
                    && complaint.getReviewedAt() != null;
            }
            return false;
        });
        if (!paid) throw ApiExceptions.conflict(
            "Khoản trả trước khi đặt kho chưa được xác minh đã thanh toán hoặc cần đối soát; chưa thể check-in."
        );
    }

    /**
     * A scheduled check-in is authoritative for the handover workflow. Legacy
     * records can still retain UNIT_RESERVED after scheduling, so they must be
     * resumable instead of forcing the operator back through the schedule step.
     */
    private boolean isCheckInReadyStatus(ReservationStatus status) {
        return status == ReservationStatus.UNIT_RESERVED
            || status == ReservationStatus.READY_FOR_CHECKIN
            || status == ReservationStatus.PREPARING;
    }

    private void recordWorkflowAudit(
        User operator,
        Reservation reservation,
        CheckIn checkIn,
        String action,
        CheckInStatus previousStatus,
        CheckInStatus currentStatus
    ) {
        auditLogService.recordMutation(
            operator, action, "CheckIn", checkIn.getId(), reservation.getFacility().getId(),
            Map.of("checkInStatus", previousStatus),
            Map.of("checkInStatus", currentStatus)
        );
    }

    private void validateWorkflowEvidence(UUID checkInId, UUID evidenceFileId, String label) {
        FileAsset asset = fileAssetRepository.findById(evidenceFileId)
            .orElseThrow(() -> ApiExceptions.validation(label + " file was not found", null));
        boolean supportedType = asset.getContentType() != null
            && (asset.getContentType().startsWith("image/")
                || "application/pdf".equalsIgnoreCase(asset.getContentType()));
        if (!supportedType || asset.getStatus() != FileAssetStatus.ACTIVE
            || !"CHECK_IN".equals(asset.getEntityType())
            || !checkInId.equals(asset.getEntityId())) {
            throw ApiExceptions.validation(
                label + " must be an active image or PDF linked to this check-in", null
            );
        }
    }

    private Instant effectiveAppointment(Reservation reservation, CheckIn checkIn) {
        if (reservation.getAppointmentAt() != null) return reservation.getAppointmentAt();
        return checkIn == null ? null : checkIn.getScheduledAt();
    }

    private StorageUnit lockAssignedUnit(UnitAssignment assignment) {
        return storageUnitRepository.findByIdForUpdate(assignment.getStorageUnit().getId())
            .orElseThrow(() -> ApiExceptions.notFound("Storage unit was not found"));
    }

    private UnitAssignment requireLockedIntakeReady(Reservation reservation, CheckIn checkIn) {
        UnitAssignment assignment = activeAssignment(reservation.getId());
        requireIntakeReady(reservation, assignment, lockAssignedUnit(assignment), effectiveAppointment(reservation, checkIn));
        return assignment;
    }

    private void requireIntakeReady(Reservation reservation, UnitAssignment assignment,
                                    StorageUnit unit, Instant appointmentAt) {
        if (appointmentAt == null) throw ApiExceptions.conflict("Hồ sơ chưa có lịch hẹn nhận kho.");
        requireHandoverWithinDeadline(appointmentAt);
        if ((reservation.getStatus() != ReservationStatus.UNIT_RESERVED
            && reservation.getStatus() != ReservationStatus.READY_FOR_CHECKIN)
            || assignment.getStatus() != UnitAssignmentStatus.ACTIVE) {
            throw ApiExceptions.conflict("Hồ sơ chưa sẵn sàng nhận kho.");
        }
        assertReservationUnitMatch(reservation, assignment, unit);
        if (unit.getStatus() != StorageUnitStatus.reserved
            || rentalRepository.existsActiveRental(reservation.getId(), unit.getId(), RentalStatus.active)) {
            throw ApiExceptions.conflict("Gian kho chưa sẵn sàng bàn giao cho đơn này.");
        }
        requirePaidReservationDeposit(reservation);
    }

    private IntakeAction intakeAction(ActorPrincipal actor, Reservation reservation,
                                      CheckIn checkIn, UnitAssignment assignment) {
        if (!actor.hasPermission(SystemPermission.PERFORM_CHECKIN)
            || !facilityScopeService.canAccessFacility(actor, reservation.getFacility().getId(), FacilityScopeLevel.OPERATE)) {
            return IntakeAction.NONE;
        }
        CheckInStatus status = checkIn == null ? null : checkIn.getStatus();
        if (status != null) {
            switch (status) {
                case completed, no_show, cancelled, rejected -> { return IntakeAction.NONE; }
                case handover_pending_receipt, setup_in_progress, setup_completed -> { return IntakeAction.CONTINUE; }
                default -> { }
            }
        }
        try {
            requireIntakeReady(reservation, assignment, assignment.getStorageUnit(), effectiveAppointment(reservation, checkIn));
        } catch (ApiException ex) {
            if (ex.getStatus() != org.springframework.http.HttpStatus.CONFLICT) throw ex;
            return IntakeAction.NONE;
        }
        return status == null || status == CheckInStatus.scheduled ? IntakeAction.START : IntakeAction.CONTINUE;
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

    private User resolveAssignedStaff(UUID assignedStaffId, User operator, UUID facilityId) {
        User assigned = assignedStaffId == null
            ? operator
            : userRepository.findById(assignedStaffId)
                .orElseThrow(() -> ApiExceptions.notFound("Assigned Staff user was not found"));
        boolean eligible = assigned.getStatus() == UserStatus.ACTIVE
            && assigned.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.STAFF)
            && userRepository.findEligibleStaff(
                UserStatus.ACTIVE,
                RoleCode.STAFF,
                facilityId,
                List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
            ).stream().anyMatch(user -> user.getId().equals(assigned.getId()));
        if (!eligible) {
            throw ApiExceptions.forbidden(
                "Assigned user must be an active Staff with facility OPERATE scope"
            );
        }
        return assigned;
    }

    private CheckInResponse toResponse(
        ActorPrincipal actor,
        Reservation reservation,
        CheckIn checkIn,
        UnitAssignment assignment
    ) {
        StorageUnit unit = assignment.getStorageUnit();
        Rental rental = checkIn == null ? null : checkIn.getRental();
        var pricing=pricingSnapshotRepository==null?null:pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        Integer setupDays=ReservationSnapshotView.pricingComplete(reservation,pricing)?pricing.getFreeSetupDays():null;
        var goodsItems = goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId());
        BigDecimal[] declaredDimensions = declaredGoodsDimensions(goodsItems);
        Instant receiptConfirmationDueAt = checkIn != null
            && checkIn.getStatus() == CheckInStatus.handover_pending_receipt
            ? ReservationCheckInPolicy.customerReceiptConfirmationDueAt(checkIn.getCheckedInAt())
            : null;
        boolean receiptConfirmationOverdue = receiptConfirmationDueAt != null
            && !receiptConfirmationDueAt.isAfter(Instant.now());
        Instant receiptConfirmationReleaseAt = checkIn != null
            && checkIn.getStatus() == CheckInStatus.handover_pending_receipt
            ? ReservationCheckInPolicy.customerReceiptReleaseAt(checkIn.getCheckedInAt())
            : null;
        boolean receiptReleaseEligible = receiptConfirmationReleaseAt != null
            && !receiptConfirmationReleaseAt.isAfter(Instant.now());
        String paymentEvidenceReference = checkIn == null || checkIn.getPaymentCollectedAt() == null || paymentRepository == null
            ? null : paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(
                reservation.getId(), PaymentType.RENTAL_BALANCE)
            .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
            .map(payment -> payment.getReceiptFileId() != null ? payment.getReceiptFileId()
                : paymentProvenance == null ? null : paymentProvenance.manualReceiptFileId(payment))
            .map(UUID::toString)
            .orElse(null);
        PalletInventoryService.StorageLimits storageLimits = palletInventory == null
            ? null : palletInventory.storageLimits(unit.getUnitType());
        CheckInResponse.StorageCapacity storageCapacity = new CheckInResponse.StorageCapacity(
            unit.getUnitType().getVolumeM3(),
            palletInventory == null ? unit.getUnitType().getMaxLoadKg() : palletInventory.cargoLimitKg(unit.getUnitType()),
            storageLimits == null ? null : storageLimits.maxCargoLengthCm(),
            storageLimits == null ? null : storageLimits.maxCargoWidthCm(),
            storageLimits == null ? null : storageLimits.maxCargoHeightCm(),
            storageLimits == null ? null : storageLimits.maxCargoWeightKg()
        );
        CheckInResponse.InventoryVisit inventoryVisit = checkIn == null ? null : new CheckInResponse.InventoryVisit(
            checkIn.getInventoryAppointmentAt(),
            checkIn.getInventoryArrivedAt(),
            checkIn.getInventoryIdentityVerifiedAt(),
            checkIn.getInventoryIdentityVerifiedBy() == null ? null : checkIn.getInventoryIdentityVerifiedBy().getFullName(),
            checkIn.getInventoryIdentityPhotoId()
        );
        return new CheckInResponse(
            checkIn == null ? null : checkIn.getId(),
            checkIn == null ? null : checkIn.getStatus(),
            checkIn == null ? null : checkIn.getScheduledAt(),
            reservation.getAppointmentAt(),
            checkIn == null ? null : checkIn.getCheckedInAt(),
            checkIn == null ? null : checkIn.getIdentityVerifiedAt(),
            checkIn == null ? null : checkIn.getVerifiedIdentityDocument(),
            checkIn == null ? null : checkIn.getIdentityVerificationNote(),
            checkIn == null ? null : checkIn.getContractSignedAt(),
            checkIn == null ? null : checkIn.getContractEvidenceReference(),
            checkIn == null ? null : checkIn.getPaymentCollectedAt(),
            checkIn == null ? null : checkIn.getSetupStartedAt(),
            checkIn == null ? null : checkIn.getSetupDueAt(),
            setupDays,
            rental == null ? null : rental.getStartDate(),
            checkIn == null ? null : checkIn.getSetupCompletedAt(),
            checkIn == null ? null : checkIn.getInventoryVerifiedAt(),
            rental == null ? null : rental.getInventoryDueAt(),
            rental != null && rental.isInventoryPending(),
            checkIn == null ? null : checkIn.getLockReference(),
            checkIn == null ? null : checkIn.getReadinessNote(),
            checkIn == null ? null : checkIn.getRejectionReason(),
            checkIn == null ? null : checkIn.getRejectionDisposition(),
            checkIn == null ? null : checkIn.getPerformedBy().getId(),
            checkIn == null ? null : checkIn.getPerformedBy().getFullName(),
            checkIn == null || checkIn.getAssignedStaff() == null ? null : checkIn.getAssignedStaff().getId(),
            checkIn == null || checkIn.getAssignedStaff() == null ? null : checkIn.getAssignedStaff().getFullName(),
            reservation.getId(), reservation.getReservationCode(), reservation.getStatus(),
            reservation.getCustomer().getId(), reservation.getCustomer().getFullName(),
            reservation.getCustomer().getEmail(), reservation.getIdentityDocumentNumber(),
            reservation.getCustomer().getPhone(), reservation.getFacility().getId(),
            reservation.getFacility().getName(), unit.getId(), unit.getCode(), unit.getStatus(),
            assignment.getId(), assignment.getStatus(), reservation.getStartDate(),
            reservation.getEndDate(), declaredPackageCount(goodsItems),
            reservation.getTotalGoodsWeightKg(),
            reservation.getTotalGoodsVolumeM3(), declaredDimensions[0], declaredDimensions[1],
            declaredDimensions[2], dueAtCheckIn(reservation.getId()), readHandover(checkIn),
            pricing == null ? null : pricing.getSetupEarlyExitPolicyVersion(),
            pricing == null ? null : pricing.getSetupEarlyExitFee(),
            goodsItems.stream()
                .map(item -> new CheckInResponse.DeclaredGoodsLine(item.getId(), item.getGoodsCode(),
                    item.getCustomGoodsName() == null || item.getCustomGoodsName().isBlank()
                        ? item.getCategoryName() : item.getCustomGoodsName(),
                    item.getCategoryName(), declaredGoodsMaterial(item),
                    item.getQuantity(), item.getLengthCm(), item.getWidthCm(), item.getHeightCm(), item.getWeightKg(),
                    item.getCatalogCategoryId(), item.getCatalogMaterialId(), item.getCategory(), item.isFragile(), item.isStackable(),
                    item.isAllowHorizontalPlacement(), item.getMaxStackLayers()))
                .toList(),
            intakeAction(actor, reservation, checkIn, assignment),
            rental == null ? null : rental.getInventoryFinalDeadlineAt(),
            checkIn == null ? null : checkIn.getInventoryResolutionStatus(),
            checkIn != null && checkIn.isInventoryFullFit(),
            checkIn != null && checkIn.isInventoryPartialFitAvailable(),
            checkIn == null ? null : checkIn.getInventorySuggestedPackageCount(),
            checkIn == null ? null : checkIn.getInventoryMismatchSummary(),
            checkIn == null ? null : checkIn.getInventoryAcceptedQuantitiesJson(),
            checkIn == null ? null : checkIn.getInventoryRemainderRemovedAt(),
            checkIn == null ? null : checkIn.getInventoryRemainderRemovalNote(),
            checkIn == null ? null : checkIn.getInventoryActualJson(),
            checkIn == null ? null : checkIn.getInventoryMismatchJson(),
            checkIn == null ? null : checkIn.getInventorySuggestedQuantitiesJson(),
            receiptConfirmationDueAt,
            receiptConfirmationOverdue,
            receiptConfirmationReleaseAt,
            receiptReleaseEligible,
            reservation.getGoodsCondition(),
            paymentEvidenceReference,
            inventoryVisit,
            checkInDeadline(reservation),
            storageCapacity
        );
    }

    private Instant checkInDeadline(Reservation reservation) {
        if (reservation.getCreatedAt() == null) return null;
        return ReservationCheckInPolicy.plannedWindow(
            reservation.getCreatedAt(), reservation.getGoodsReviewStatus() != GoodsReviewStatus.NOT_REQUIRED
        ).deadlineAt();
    }

    private BigDecimal dueAtCheckIn(UUID reservationId) {
        if (pricingSnapshotRepository == null) {
            return null;
        }
        return pricingSnapshotRepository.findByReservation_Id(reservationId)
            .map(snapshot -> snapshot.getDueAtCheckIn())
            .orElse(null);
    }

    private void completeInventoryTask(Rental rental, Instant completedAt, String notes) {
        maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
            rental.getId(),
            MaintenanceTaskType.inventory_check,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        ).forEach(task -> {
            task.setStatus(MaintenanceTaskStatus.completed);
            task.setCompletedAt(completedAt);
            task.setResultReport(clean(notes) == null
                ? "Đã hoàn tất kiểm kê đầu vào trong luồng check-in."
                : clean(notes));
            maintenanceTaskRepository.save(task);
        });
    }

    private void createInventoryTask(Rental rental, Instant now) {
        if (maintenanceTaskRepository == null
            || rental.getInventoryTaskCreatedAt() != null
            || maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
                rental.getId(),
                MaintenanceTaskType.inventory_check,
                List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
            ).stream().findAny().isPresent()) {
            return;
        }
        MaintenanceTask task = new MaintenanceTask();
        task.setFacility(rental.getFacility());
        task.setStorageUnit(rental.getStorageUnit());
        task.setRental(rental);
        task.setTaskType(MaintenanceTaskType.inventory_check);
        task.setTitle("Kiểm kê hàng hóa đầu vào · " + rental.getStorageUnit().getCode());
        task.setReason(
            "Khách đã báo chuẩn bị hàng xong cho đơn "
                + rental.getReservation().getReservationCode()
                + ". Staff cần kiểm kê hàng hóa, không kiểm kê lại gian kho trống."
        );
        task.setPriority(com.storagehub.domain.model.TaskPriority.medium);
        task.setReportedBy(null);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(rental.getInventoryFinalDeadlineAt() == null && rental.getInventoryDueAt() == null
            ? null
            : (rental.getInventoryFinalDeadlineAt() == null ? rental.getInventoryDueAt() : rental.getInventoryFinalDeadlineAt())
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate());
        task.setDeadlineAt(rental.getInventoryFinalDeadlineAt() == null
            ? rental.getInventoryDueAt() : rental.getInventoryFinalDeadlineAt());
        maintenanceTaskRepository.saveAndFlush(task);
        rental.setInventoryTaskCreatedAt(now);
    }

    private void scheduleInventoryDeadlines(Rental rental, Instant readyAt) {
        rental.setInventoryDueAt(InventoryTimelinePolicy.reminderAt(readyAt));
        rental.setInventoryFinalDeadlineAt(InventoryTimelinePolicy.inventoryDeadline(readyAt));
        rental.setInventoryDeadlineReminderAt(null);
        rental.setInventoryEscalatedAt(null);
        rental.setInventoryPending(true);
    }

    private void requireRemainingPayment(Reservation reservation) {
        if (pricingSnapshotRepository == null || paymentRepository == null) {
            throw ApiExceptions.conflict(ReservationSnapshotView.MISSING+": nguồn dữ liệu thanh toán");
        }
        var snapshot = pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElse(null);
        ReservationSnapshotView.requireSetupDays(reservation,snapshot);
        if(snapshot.getDueAtCheckIn()==null||snapshot.getDueAtCheckIn().signum()<0)
            throw ApiExceptions.conflict(ReservationSnapshotView.MISSING+": số tiền còn lại khi nhận kho");
        if (snapshot.getDueAtCheckIn().signum() == 0) {
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
            payment.getInitiatedBy().getFullName(), payment.getInvoiceNumber(), payment.getInvoiceStatus(),
            payment.getInvoiceIssuedAt()
        );
    }

    private Integer declaredPackageCount(List<ReservationGoodsItem> goodsItems) {
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
    private BigDecimal[] declaredGoodsDimensions(List<ReservationGoodsItem> goodsItems) {
        BigDecimal[] largest = {null, null, null};
        for (var item : goodsItems) {
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

    private record InventoryFitEvaluation(
        boolean fullFit,
        boolean partialFitAvailable,
        int suggestedPackageCount,
        String summary,
        Map<UUID, Integer> suggestedQuantities
    ) {}

    private InventoryFitEvaluation evaluateInventoryFit(
        Reservation reservation,
        StorageUnit unit,
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request,
        boolean requireSafePlan
    ) {
        Map<UUID, ReservationGoodsItem> declaredById = new HashMap<>();
        declared.forEach(item -> declaredById.put(item.getId(), item));
        List<GoodsItemRequest> actualItems = new ArrayList<>();
        Map<UUID, Integer> actualQuantities = new LinkedHashMap<>();
        for (CompleteCheckInRequest.InventoryLine line : request.inventoryLines()) {
            ReservationGoodsItem original = declaredById.get(line.goodsItemId());
            if (original == null || line.actualQuantity() == null || line.actualQuantity() <= 0) continue;
            actualQuantities.put(line.goodsItemId(), line.actualQuantity());
            actualItems.add(actualGoodsItem(original, line));
        }

        boolean aggregateFit = request.actualMeasurements().weightKg().compareTo(unit.getUnitType().getMaxLoadKg()) <= 0
            && request.actualMeasurements().actualVolumeM3().compareTo(unit.getUnitType().getVolumeM3()) <= 0
            && dimensionsFit(request, unit);
        PalletInventoryService.Fit calculated = palletInventory == null
            ? null : palletInventory.evaluateActualFit(unit, actualItems);
        if (requireSafePlan && (calculated == null || calculated.layoutMissing() || calculated.reviewRequired()))
            throw ApiExceptions.conflict("Chưa xác định được phương án xếp an toàn. Vui lòng kiểm tra dữ liệu hàng và bố trí gian kho.");
        boolean fullFit = calculated == null || calculated.layoutMissing() || calculated.reviewRequired()
            ? aggregateFit : calculated.plan() != null;
        if (fullFit) {
            return new InventoryFitEvaluation(true, false, request.packageCount(),
                inventoryMismatchSummary(reservation, declared, request, false, false), actualQuantities);
        }

        Map<UUID, Integer> suggested = largestFittingSubset(unit, declared, request);
        int suggestedCount = suggested.values().stream().mapToInt(Integer::intValue).sum();
        String summary = inventoryMismatchSummary(reservation, declared, request, true, suggestedCount > 0);
        return new InventoryFitEvaluation(false, suggestedCount > 0, suggestedCount, summary, suggested);
    }

    private Map<UUID, Integer> largestFittingSubset(
        StorageUnit unit,
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request
    ) {
        if (palletInventory == null) return Map.of();
        Map<UUID, ReservationGoodsItem> declaredById = new HashMap<>();
        declared.forEach(item -> declaredById.put(item.getId(), item));
        Map<UUID, Integer> accepted = new LinkedHashMap<>();
        List<GoodsItemRequest> acceptedItems = new ArrayList<>();
        for (CompleteCheckInRequest.InventoryLine line : request.inventoryLines()) {
            if (line.actualQuantity() == null || line.actualQuantity() <= 0) continue;
            ReservationGoodsItem original = declaredById.get(line.goodsItemId());
            if (original == null) continue;
            int low = 0;
            int high = line.actualQuantity();
            while (low < high) {
                int candidate = low + (high - low + 1) / 2;
                List<GoodsItemRequest> trial = new ArrayList<>(acceptedItems);
                GoodsItemRequest item = actualGoodsItem(original, line);
                item.setQuantity(candidate);
                trial.add(item);
                PalletInventoryService.Fit result = palletInventory.evaluateActualFit(unit, trial);
                if (result.plan() != null) low = candidate;
                else high = candidate - 1;
            }
            if (low > 0) {
                GoodsItemRequest item = actualGoodsItem(original, line);
                item.setQuantity(low);
                acceptedItems.add(item);
                accepted.put(line.goodsItemId(), low);
            }
        }
        return accepted;
    }

    private boolean validateActualGoods(StorageUnit unit, List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request, boolean strict) {
        var originals = new HashMap<UUID, ReservationGoodsItem>();
        declared.forEach(item -> originals.put(item.getId(), item));
        boolean rejected = false;
        for (int i = 0; i < request.inventoryLines().size(); i++) {
            var line = request.inventoryLines().get(i);
            if (line.actualQuantity() == null || line.actualQuantity() == 0) continue;
            var actual = line.actualGoods();
            if (actual == null) {
                if (strict) throw ApiExceptions.validation("Vui lòng đối chiếu loại hàng, chất liệu và điều kiện xếp cho từng dòng.", null);
                continue;
            }
            if (actual.getCatalogCategoryId() == null || actual.getStackable() == null || actual.getAllowHorizontalPlacement() == null)
                throw ApiExceptions.validation("Vui lòng chọn loại hàng trong danh mục và ghi nhận điều kiện xếp thực tế.", null);
            if (goodsCatalogService == null) throw ApiExceptions.conflict("Chưa thể đối chiếu danh mục hàng hóa.");
            try {
                goodsCatalogService.validateForUnitType(unit.getUnitType(), actual, i);
            } catch (com.storagehub.common.api.ApiException e) {
                if (e.getStatus() != org.springframework.http.HttpStatus.BAD_REQUEST) throw e;
                rejected = true;
            }
        }
        return rejected;
    }

    private boolean actualLineVariance(List<ReservationGoodsItem> declared, CompleteCheckInRequest request) {
        if (request.inventoryLines() == null) return false;
        var originals = new HashMap<UUID, ReservationGoodsItem>();
        declared.forEach(item -> originals.put(item.getId(), item));
        for (var line : request.inventoryLines()) {
            if (line.actualGoods() == null || line.actualQuantity() == null || line.actualQuantity() == 0) continue;
            var original = originals.get(line.goodsItemId());
            var actual = line.actualGoods();
            if (original == null || !Objects.equals(original.getCatalogCategoryId(), actual.getCatalogCategoryId())
                || !Objects.equals(original.getCatalogMaterialId(), actual.getCatalogMaterialId())
                || original.isFragile() != actual.isFragile()
                || original.isStackable() != Boolean.TRUE.equals(actual.getStackable())
                || original.isAllowHorizontalPlacement() != actual.allowsHorizontalPlacement()
                || original.getMaxStackLayers() != actual.getMaxStackLayers()) return true;
        }
        return false;
    }

    private GoodsItemRequest actualGoodsItem(
        ReservationGoodsItem original,
        CompleteCheckInRequest.InventoryLine line
    ) {
        GoodsItemRequest item = line.actualGoods() == null ? new GoodsItemRequest()
            : objectMapper.convertValue(line.actualGoods(), GoodsItemRequest.class);
        if (line.actualGoods() == null) {
        item.setCategory(original.getCategory());
        item.setCatalogCategoryId(original.getCatalogCategoryId());
        item.setCatalogMaterialId(original.getCatalogMaterialId());
        item.setCategoryCode(original.getCategoryCode());
        item.setCategoryName(original.getCategoryName());
        item.setMaterialCode(original.getMaterialCode());
        item.setMaterialName(original.getMaterialName());
        item.setCustomMaterial(original.getCustomMaterial());
        item.setCustomGoodsName(original.getCustomGoodsName());
        item.setFragile(original.isFragile());
        item.setStackable(original.isStackable());
        item.setMaxStackLayers(original.getMaxStackLayers());
        item.setAllowHorizontalPlacement(original.isAllowHorizontalPlacement());
        }
        item.setDescription(original.getDescription() == null ? "Hàng hóa kiểm kê thực tế" : original.getDescription());
        item.setCustomerNote(original.getCustomerNote());
        item.setQuantity(line.actualQuantity());
        var measurements = line.measurements();
        item.setLengthCm(measurements == null ? original.getLengthCm() : measurements.lengthCm());
        item.setWidthCm(measurements == null ? original.getWidthCm() : measurements.widthCm());
        item.setHeightCm(measurements == null ? original.getHeightCm() : measurements.heightCm());
        BigDecimal totalWeight = measurements == null ? original.getWeightKg()
            : measurements.weightKg();
        item.setWeightPerItemKg(line.actualQuantity() == null || line.actualQuantity() <= 0
            ? totalWeight : totalWeight.divide(BigDecimal.valueOf(line.actualQuantity()), 6, java.math.RoundingMode.HALF_UP));
        return item;
    }

    private String inventoryMismatchSummary(
        Reservation reservation,
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request,
        boolean packingMismatch,
        boolean partialFit
    ) {
        List<String> labels = new ArrayList<>();
        if (packagingVariance(reservation, request)) labels.add("phương thức hoặc tình trạng đóng gói");
        if (hasPackageCountVariance(declared, request)) labels.add("số kiện");
        if (hasMeasurementVariance(reservation, request, declared)) {
            if (!labels.contains("khối lượng")) labels.add("khối lượng");
            if (!labels.contains("thể tích")) labels.add("thể tích hoặc kích thước");
        }
        if (hasGoodsDeclarationVariance(declared, request) || actualLineVariance(declared, request)) labels.add("loại hàng, chất liệu hoặc điều kiện xếp từng dòng");
        if (request.inventoryLines() != null && request.inventoryLines().stream()
            .anyMatch(line -> line.condition() == CompleteCheckInRequest.InventoryLine.Condition.DAMAGED)) {
            labels.add("tình trạng hàng hóa");
        }
        if (packingMismatch) labels.add(partialFit ? "sức chứa, có thể nhận một phần" : "sức chứa hoặc cách xếp");
        return labels.isEmpty() ? null : String.join(", ", labels);
    }

    private boolean packagingVariance(Reservation reservation, CompleteCheckInRequest request) {
        boolean methodMismatch = request.actualPackagingMethod() != null
            && !request.actualPackagingMethod().trim().equalsIgnoreCase(
                reservation.getGoodsCondition() == null ? "" : reservation.getGoodsCondition().trim());
        return methodMismatch || request.packagingCondition() == CompleteCheckInRequest.PackagingCondition.MINOR_DAMAGE
            || request.packagingCondition() == CompleteCheckInRequest.PackagingCondition.MAJOR_DAMAGE;
    }

    private String writeInventoryActual(CompleteCheckInRequest request, InventoryFitEvaluation fit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("packagingMethod", request.actualPackagingMethod());
        value.put("packagingCondition", request.packagingCondition());
        value.put("packageCount", request.packageCount());
        value.put("lines", request.inventoryLines());
        value.put("weightKg", request.actualMeasurements().weightKg());
        value.put("volumeM3", request.actualMeasurements().actualVolumeM3());
        value.put("lengthCm", request.actualMeasurements().lengthCm());
        value.put("widthCm", request.actualMeasurements().widthCm());
        value.put("heightCm", request.actualMeasurements().heightCm());
        value.put("fullFit", fit.fullFit());
        value.put("partialFitAvailable", fit.partialFitAvailable());
        value.put("suggestedPackageCount", fit.suggestedPackageCount());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize actual inventory", exception);
        }
    }

    private String writeInventoryMismatch(
        Reservation reservation,
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request,
        InventoryFitEvaluation fit
    ) {
        Map<String, Object> declaredValues = new LinkedHashMap<>();
        declaredValues.put("packageCount", declaredPackageCount(declared));
        declaredValues.put("lines", declared.stream().map(item -> Map.of(
            "goodsItemId", item.getId(), "category", Objects.toString(declaredGoodsCategory(item), "Chưa ghi nhận"),
            "material", Objects.toString(declaredGoodsMaterial(item), "Chưa ghi nhận"), "quantity", item.getQuantity())).toList());
        declaredValues.put("packagingMethod", reservation.getGoodsCondition());
        declaredValues.put("weightKg", reservation.getTotalGoodsWeightKg());
        declaredValues.put("volumeM3", reservation.getTotalGoodsVolumeM3());
        declaredValues.put("category", declared.stream().map(this::declaredGoodsCategory).filter(Objects::nonNull).distinct().toList());
        declaredValues.put("material", declared.stream().map(this::declaredGoodsMaterial).filter(Objects::nonNull).distinct().toList());
        declaredValues.put("catalogLines", declared.stream().map(item -> {
            Map<String, Object> ids = new LinkedHashMap<>();
            ids.put("goodsItemId", item.getId());
            ids.put("categoryId", item.getCatalogCategoryId());
            ids.put("materialId", item.getCatalogMaterialId());
            return ids;
        }).toList());

        Map<String, Object> actualValues = new LinkedHashMap<>();
        actualValues.put("packageCount", request.packageCount());
        actualValues.put("lines", request.inventoryLines());
        actualValues.put("packagingMethod", request.actualPackagingMethod());
        actualValues.put("packagingCondition", request.packagingCondition());
        actualValues.put("weightKg", request.actualMeasurements().weightKg());
        actualValues.put("volumeM3", request.actualMeasurements().actualVolumeM3());
        actualValues.put("lengthCm", request.actualMeasurements().lengthCm());
        actualValues.put("widthCm", request.actualMeasurements().widthCm());
        actualValues.put("heightCm", request.actualMeasurements().heightCm());
        actualValues.put("category", request.goodsCategory());
        actualValues.put("material", request.goodsMaterial());
        actualValues.put("inventoryLines", request.inventoryLines());

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("declared", declaredValues);
        value.put("actual", actualValues);
        value.put("summary", inventoryMismatchSummary(reservation, declared, request, !fit.fullFit(), fit.partialFitAvailable()));
        value.put("fullFit", fit.fullFit());
        value.put("partialFitAvailable", fit.partialFitAvailable());
        value.put("suggestedPackageCount", fit.suggestedPackageCount());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize inventory mismatch", exception);
        }
    }

    private String writeSuggestedQuantities(Map<UUID, Integer> quantities) {
        try {
            return objectMapper.writeValueAsString(quantities == null ? Map.of() : quantities);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize inventory packing suggestion", exception);
        }
    }

    private boolean hasPackageCountVariance(
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request
    ) {
        Integer declaredCount = declaredPackageCount(declared);
        return declaredCount != null && request.packageCount() != declaredCount;
    }

    private boolean hasMeasurementVariance(
        Reservation reservation,
        CompleteCheckInRequest request,
        List<ReservationGoodsItem> declared
    ) {
        if (exceedsTolerance(request.actualMeasurements().weightKg(), reservation.getTotalGoodsWeightKg(), new BigDecimal("0.10"))
            || exceedsTolerance(request.actualMeasurements().actualVolumeM3(), reservation.getTotalGoodsVolumeM3(), new BigDecimal("0.000100"))) {
            return true;
        }
        BigDecimal[] declaredDimensions = declaredGoodsDimensions(declared);
        if (declaredDimensions[0] == null) return false;
        BigDecimal[] actualDimensions = {
            request.actualMeasurements().lengthCm(),
            request.actualMeasurements().widthCm(),
            request.actualMeasurements().heightCm()
        };
        Arrays.sort(actualDimensions);
        for (int index = 0; index < actualDimensions.length; index++) {
            if (exceedsTolerance(actualDimensions[index], declaredDimensions[index], new BigDecimal("0.5"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGoodsDeclarationVariance(
        List<ReservationGoodsItem> declared,
        CompleteCheckInRequest request
    ) {
        var lines = request.inventoryLines() == null ? List.<CompleteCheckInRequest.InventoryLine>of() : request.inventoryLines();
        var byId = new HashMap<UUID, CompleteCheckInRequest.InventoryLine>();
        lines.forEach(line -> byId.put(line.goodsItemId(), line));
        for (var item : declared) {
            var line = byId.get(item.getId());
            if (line == null || line.actualQuantity() == 0 || item.getCatalogCategoryId() == null) continue;
            if (!Objects.equals(item.getCatalogCategoryId(), line.actualCategoryId())
                || !Objects.equals(item.getCatalogMaterialId(), line.actualMaterialId())) return true;
        }
        var legacy = lines.isEmpty() ? declared : declared.stream()
            .filter(item -> item.getCatalogCategoryId() == null)
            .filter(item -> !byId.containsKey(item.getId()) || byId.get(item.getId()).actualQuantity() > 0)
            .toList();
        List<String> categories = legacy.stream().map(this::declaredGoodsCategory)
            .filter(value -> value != null).distinct().toList();
        List<String> materials = legacy.stream().map(this::declaredGoodsMaterial)
            .filter(value -> value != null).distinct().toList();
        return !categories.isEmpty() && !matchesDeclaredLabel(request.goodsCategory(), categories)
            || !materials.isEmpty() && !matchesDeclaredLabel(request.goodsMaterial(), materials);
    }

    private CompleteCheckInRequest readHandover(CheckIn checkIn) {
        if (checkIn == null || checkIn.getStatus() != CheckInStatus.completed && checkIn.getInventoryActualJson() == null
            || checkIn.getChecklistJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(checkIn.getChecklistJson(), CompleteCheckInRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read check-in handover", exception);
        }
    }

    private void validateInventoryLines(List<ReservationGoodsItem> declared, CompleteCheckInRequest request) {
        if (request.inventoryLines() == null) {
            if (declared.stream().anyMatch(item -> item.getCatalogCategoryId() != null))
                throw ApiExceptions.validation("Cần đối chiếu mã loại hàng và chất liệu theo từng dòng kiểm kê.", null);
            return;
        }
        var lines = request.inventoryLines();
        if (declared.size() != lines.size()) {
            throw ApiExceptions.validation("Cần kiểm kê đủ từng dòng hàng đã khai báo.", null);
        }
        if (declared.isEmpty()) return;

        var byId = new HashMap<UUID, ReservationGoodsItem>();
        declared.forEach(item -> byId.put(item.getId(), item));
        var seen = new HashSet<UUID>();
        long total = 0;
        boolean hasMeasurements = lines.stream().anyMatch(line -> line.measurements() != null);
        BigDecimal[] largest = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (var line : lines) {
            var item = byId.get(line.goodsItemId());
            if (item == null || !seen.add(line.goodsItemId())) {
                throw ApiExceptions.validation("Dòng hàng kiểm kê bị trùng hoặc không thuộc đơn đặt kho.", null);
            }
            int actual = line.actualQuantity();
            int expected = item.getQuantity();
            if (actual > 0 && (item.getCatalogCategoryId() != null || line.actualCategoryId() != null || line.actualMaterialId() != null)) {
                GoodsCatalogService catalog = inventoryCatalog != null ? inventoryCatalog : goodsCatalogService;
                if (catalog == null) throw ApiExceptions.conflict("Chưa thể đối chiếu danh mục hàng hóa.");
                catalog.validateInventorySelection(line.actualCategoryId(), line.actualMaterialId(),
                    item.getCatalogCategoryId(), item.getCatalogMaterialId());
            }
            var condition = line.condition();
            if ((actual == 0) != (condition == CompleteCheckInRequest.InventoryLine.Condition.NOT_PRESENT)) {
                throw ApiExceptions.validation("Tình trạng hàng phải phù hợp với số kiện thực tế.", null);
            }
            var measurements = line.measurements();
            if (hasMeasurements && (actual > 0) != (measurements != null)) {
                throw ApiExceptions.validation("Cần nhập số đo cho từng dòng hàng có kiện thực tế.", null);
            }
            if (measurements != null) {
                BigDecimal[] dimensions = {measurements.lengthCm(), measurements.widthCm(), measurements.heightCm()};
                Arrays.sort(dimensions);
                for (int i = 0; i < dimensions.length; i++) {
                    largest[i] = largest[i].max(dimensions[i]);
                }
                weight = weight.add(measurements.weightKg());
                volume = volume.add(dimensions[0].multiply(dimensions[1]).multiply(dimensions[2])
                    .multiply(BigDecimal.valueOf(actual)).movePointLeft(6));
            }
            if ((actual != expected || condition == CompleteCheckInRequest.InventoryLine.Condition.DAMAGED)
                && (line.note() == null || line.note().isBlank())) {
                throw ApiExceptions.validation("Cần ghi chú cho dòng hàng thiếu, thừa hoặc hư hỏng.", null);
            }
            total += actual;
        }
        if (total != request.packageCount()) {
            throw ApiExceptions.validation("Tổng số kiện phải bằng tổng số kiện của các dòng kiểm kê.", null);
        }
        if (hasMeasurements) {
            var summary = request.actualMeasurements();
            BigDecimal[] dimensions = {summary.lengthCm(), summary.widthCm(), summary.heightCm()};
            Arrays.sort(dimensions);
            if (dimensions[0].compareTo(largest[0]) != 0 || dimensions[1].compareTo(largest[1]) != 0
                || dimensions[2].compareTo(largest[2]) != 0
                || summary.weightKg().subtract(weight).abs().compareTo(new BigDecimal("0.000000001")) > 0
                || summary.actualVolumeM3().subtract(volume).abs().compareTo(new BigDecimal("0.000000001")) > 0) {
                throw ApiExceptions.validation("Số đo tổng hợp phải khớp kết quả kiểm kê từng dòng hàng.", null);
            }
        }
    }

    private void validateVariance(Reservation reservation, CompleteCheckInRequest request, List<ReservationGoodsItem> goodsItems) {
        Integer declaredPackageCount = declaredPackageCount(goodsItems);
        boolean packageCountVaries = declaredPackageCount != null
            && request.packageCount() != declaredPackageCount;
        boolean weightVaries = exceedsTolerance(
            request.actualMeasurements().weightKg(), reservation.getTotalGoodsWeightKg(), new BigDecimal("0.10")
        );
        boolean volumeVaries = exceedsTolerance(
            request.actualMeasurements().actualVolumeM3(), reservation.getTotalGoodsVolumeM3(), new BigDecimal("0.000100")
        );
        BigDecimal[] declaredDimensions = declaredGoodsDimensions(goodsItems);
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
            if (request.inventoryLines() != null) {
                if (request.varianceReason() == null || request.varianceReason().isBlank()) {
                    throw ApiExceptions.validation("Cần ghi rõ lý do khi hàng thực tế khác khai báo", null);
                }
                return;
            }
            throw ApiExceptions.conflict("Actual goods dimensions or measurements differ from the reservation and the variance is not accepted");
        }
        if (request.varianceReason() == null || request.varianceReason().isBlank()) {
            throw ApiExceptions.validation("varianceReason is required when actual goods differ", null);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private GoodsCatalogService inventoryCatalog;

    private void validateGoodsDeclaration(List<ReservationGoodsItem> declared, CompleteCheckInRequest request) {
        if (!hasGoodsDeclarationVariance(declared, request)) {
            return;
        }
        if (!request.actualMeasurements().varianceAccepted()) {
            if (request.inventoryLines() != null) {
                if (request.varianceReason() == null || request.varianceReason().isBlank()) {
                    throw ApiExceptions.validation("Cần ghi rõ lý do khi loại hàng hoặc chất liệu chính khác khai báo", null);
                }
                return;
            }
            throw ApiExceptions.conflict("Loại hàng hoặc chất liệu chính thực tế khác với thông tin đã đặt kho và chưa được chấp nhận");
        }
        if (request.varianceReason() == null || request.varianceReason().isBlank()) {
            throw ApiExceptions.validation("Cần ghi rõ lý do khi loại hàng hoặc chất liệu chính thực tế khác khai báo", null);
        }
    }

    private String declaredGoodsCategory(ReservationGoodsItem item) {
        String category = clean(item.getCategoryName());
        return category != null ? category : clean(item.getCustomGoodsName());
    }

    private String declaredGoodsMaterial(ReservationGoodsItem item) {
        String customMaterial = clean(item.getCustomMaterial());
        return customMaterial != null ? customMaterial : clean(item.getMaterialName());
    }

    private boolean matchesDeclaredLabel(String actual, List<String> declared) {
        String normalizedActual = normalizeGoodsLabel(actual);
        return normalizedActual != null && declared.stream()
            .map(this::normalizeGoodsLabel)
            .anyMatch(normalizedActual::equals);
    }

    private String normalizeGoodsLabel(String value) {
        String normalized = clean(value);
        return normalized == null ? null : normalized.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private boolean validateIdentityDocument(Reservation reservation, CompleteCheckInRequest request) {
        validateIdentityDocument(reservation, request.identityDocumentNumber());
        return true;
    }

    private void validateIdentityDocument(Reservation reservation, String documentNumber) {
        String actualIdentity = normalizeIdentityDocument(documentNumber);
        String declaredIdentity = normalizeIdentityDocument(reservation.getIdentityDocumentNumber());
        boolean legacyIdentity = actualIdentity != null && (actualIdentity.matches("[0-9]{9}")
            || actualIdentity.matches("(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6,20}"));
        if (!legacyIdentity && !com.storagehub.validation.IdentityDocumentPolicy.isValid(actualIdentity)) {
            throw ApiExceptions.validation(com.storagehub.validation.IdentityDocumentPolicy.MESSAGE, null);
        }

        if (declaredIdentity == null) {
            throw ApiExceptions.conflict("Hồ sơ đặt kho chưa có giấy tờ để đối chiếu.");
        }
        if (!declaredIdentity.equals(actualIdentity)) {
            throw ApiExceptions.conflict("Giấy tờ không khớp với người đặt kho.");
        }
    }

    private FileAsset requireInventoryIdentityPhoto(CheckIn checkIn, UUID photoId) {
        if (photoId == null) throw ApiExceptions.validation("Cần ảnh khách tại buổi kiểm kê.", null);
        return fileAssetRepository.findById(photoId).filter(photo ->
            FileStorageService.INVENTORY_IDENTITY_ENTITY.equals(photo.getEntityType())
                && checkIn.getId().equals(photo.getEntityId())
                && photo.getStatus() == FileAssetStatus.ACTIVE
                && photo.getContentType() != null && photo.getContentType().startsWith("image/")
                && photo.getCreatedAt() != null && !photo.getCreatedAt().isBefore(checkIn.getInventoryArrivedAt())
        ).orElseThrow(() -> ApiExceptions.validation("Ảnh phải được tải cho đúng hồ sơ sau khi ghi nhận khách đến.", null));
    }

    private void validateNoPasswordHandover(CompleteCheckInRequest request) {
        if (request.handedOverItems() != null) {
            request.handedOverItems().forEach(this::validateNoPasswordReference);
        }
    }

    private void validateNoPasswordReference(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.toLowerCase(Locale.ROOT);
        if (normalized.contains("password") || normalized.contains("mật khẩu") || normalized.matches(".*\\bpin\\b.*")) {
            throw ApiExceptions.validation("New handover data must contain only the lock/key reference, not a password or PIN", null);
        }
    }

    private String normalizeIdentityDocument(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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
            throw ApiExceptions.validation("Mỗi bằng chứng phải là mã tệp đã tải lên hợp lệ", null);
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

    private void validatePaymentEvidence(UUID checkInId, UUID evidenceFileId) {
        FileAsset asset = fileAssetRepository.findById(evidenceFileId)
            .orElseThrow(() -> ApiExceptions.validation("Payment evidence file was not found", null));
        boolean supportedType = asset.getContentType() != null
            && (asset.getContentType().startsWith("image/")
                || "application/pdf".equalsIgnoreCase(asset.getContentType()));
        if (!supportedType || asset.getStatus() != FileAssetStatus.ACTIVE
            || !"CHECK_IN".equals(asset.getEntityType())
            || !checkInId.equals(asset.getEntityId())) {
            throw ApiExceptions.validation(
                "Payment evidence must be an active image or PDF linked to this check-in", null
            );
        }
    }

    private PageRequest pageRequest(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw ApiExceptions.validation("page must be >= 0 and pageSize must be between 1 and 100", null);
        }
        return PageRequest.of(page, pageSize);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireOperator(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.STAFF)) {
            throw ApiExceptions.forbidden("Only Staff can process check-in and handover");
        }
    }

    private void requireCheckInReader(ActorPrincipal actor) {
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only Staff or Manager can monitor check-in records");
        }
    }
}
