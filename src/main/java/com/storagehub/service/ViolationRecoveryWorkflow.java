package com.storagehub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.model.ViolationRecovery.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ViolationRecoveryWorkflow {
    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final int VERIFICATION_LIMIT = 2;
    private static final List<Stage> MANAGER_STAGES = List.of(
        Stage.REVIEW_REQUIRED, Stage.AWAITING_ASSIGNMENT, Stage.AWAITING_APPROVAL, Stage.BLOCKED);
    private final ViolationRecoveryRepository recoveries;
    private final RentalRepository rentals;
    private final ReturnCaseRepository returns;
    private final MaintenanceTaskRepository tasks;
    private final UserRepository users;
    private final FileAssetRepository files;
    private final AccessCredentialService access;
    private final UnitAssignmentRepository assignments;
    private final NotificationService notifications;
    private final AuditLogService audit;
    private final FacilityScopeService scopes;
    private final WorkflowBusinessCalendar calendar;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final RecoveryReviewService reviewEvidence;
    private final UserFacilityScopeRepository userFacilityScopes;
    private final RecoveryStaffAvailability staffAvailability;

    public enum Action {
        // Retired commands remain parseable so older clients receive a workflow conflict instead of performing them.
        CONFIRM_VIOLATION, ASSIGN, ACCEPT, DECLINE, WILL_ATTEND, CANNOT_ATTEND, DISPUTE,
        RESCHEDULE, RECORD_PRESENT, RECORD_ABSENT, VERIFY, APPROVE, START, SAVE_ITEM, BLOCK,
        RESUME, CANCEL, COMPLETE, SCHEDULE_PICKUP, OPEN_PICKUP, VERIFY_PICKUP, RELEASE_ITEM, FOLLOW_UP_PICKUP,
        REQUEST_FINANCE, RESOLVE_FINANCE, ASSIGN_PICKUP, CLOSE_REMEDIATION, RECORD_LIQUIDATION
    }

    public record Command(@jakarta.validation.constraints.NotNull Action action, UUID staffId, UUID witnessId, UUID evidenceId, UUID attemptId,
        Instant appointmentAt, @Size(max = 4000) String note, @Size(max = 32) String document,
        @Size(max = 80) String itemCode, @Size(max = 240) String itemName, @Size(max = 240) String condition,
        @Size(max = 240) String location, @Size(max = 40) String disposition,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) Integer quantity,
        @Size(min = 1, max = 1000) List<@Size(max = 80) String> itemCodes,
        @Size(max = 10) List<UUID> attachmentIds) {
        public Command(Action action, UUID staffId, UUID witnessId, UUID evidenceId, UUID attemptId,
            Instant appointmentAt, String note, String document, String itemCode, String itemName,
            String condition, String location, String disposition) {
            this(action, staffId, witnessId, evidenceId, attemptId, appointmentAt, note, document,
                itemCode, itemName, condition, location, disposition, null, null, null);
        }
        public Command(Action action, UUID staffId, UUID witnessId, UUID evidenceId, UUID attemptId,
            Instant appointmentAt, String note, String document, String itemCode, String itemName,
            String condition, String location, String disposition, Integer quantity, List<String> itemCodes) {
            this(action, staffId, witnessId, evidenceId, attemptId, appointmentAt, note, document,
                itemCode, itemName, condition, location, disposition, quantity, itemCodes, null);
        }
    }

    public record Liquidation(Instant recordedAt, UUID recordedBy, String note, UUID evidenceId) {}

    public record Item(String code, String name, String condition, String location, UUID evidenceId, String disposition,
        Instant releasedAt, UUID releaseEvidenceId, Liquidation liquidation) {}

    public record StaffOption(UUID id, String name) {}

    public record HistoryEvent(String action, String actorName, Instant time, Stage stage, String note) {}

    public record CommunicationEntry(String role, String actorName, String action, Instant time, String note,
        List<UUID> attachmentIds) {}

    public record Verification(int used, int limit, int remaining, boolean matched, boolean canReceiveItems, String message) {}

    @Transactional(readOnly = true)
    public List<StaffOption> staff(ActorPrincipal actor, UUID id, boolean pickup, boolean witness) {
        var v = recoveries.findById(id).filter(entry -> canRead(actor, entry) && !actor.hasRole(RoleCode.CUSTOMER))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        var d = details(v);
        Instant start = pickup ? (d.pickupAppointmentAt != null ? d.pickupAppointmentAt : Instant.now()) : v.getScheduledAt();
        Instant end = pickup ? start.plus(WINDOW) : v.getDeadlineAt();
        if (!pickup && end != null && !end.isAfter(Instant.now())) {
            start = Instant.now();
            end = start.plus(WINDOW);
        }
        final Instant slotStart = start, slotEnd = end;
        return eligibleStaff(v).stream()
            .filter(user -> !witness || v.getTask() == null || v.getTask().getAssignedTo() == null
                || !user.getId().equals(v.getTask().getAssignedTo().getId()))
            .filter(user -> staffAvailability.available(user.getId(), v, slotStart, slotEnd))
            .map(user -> new StaffOption(user.getId(), user.getFullName())).toList();
    }

    public record Entry(UUID id, UUID rentalId, UUID reservationId, String reservationCode, String customerName, UUID facilityId, String unitCode, UUID taskId,
        String staffName, Reason reason, Stage stage, Instant sourceDeadlineAt, Instant requiredAt, Instant scheduledAt,
        Instant deadlineAt, Instant actionDueAt, Instant overdueAt, Instant completedAt, Instant pickupDueAt,
        Instant pickupReviewAt, boolean escalated, boolean pickupEscalated, boolean financePending, boolean financeClosed,
        String owner, Attendance attendance, Reply reply, Instant notifiedAt, Instant responseDueAt,
        Instant arrivedAt, Instant verifiedAt, int verificationAttempts, int pickupAttempts, boolean needsWitness,
        UUID attendanceEvidenceId, UUID beforeEvidenceId, UUID afterEvidenceId, String reasonNote, String customerNote,
        String report, List<Item> items, List<HistoryEvent> history, List<Action> actions,
        Instant detectedAt, Instant reviewDueAt, Instant businessReviewDueAt, RecoveryReviewService.Review review,
        Verification verification, Verification pickupVerification, boolean canViewSourceGoods,
        Instant pickupAppointmentAt, String managerResponseNote, Instant managerRespondedAt,
        List<UUID> customerAttachmentIds, List<UUID> managerAttachmentIds,
        List<CommunicationEntry> communications) {}

    public static final class Details {
        public Instant notifiedAt, responseDueAt, arrivedAt, verifiedAt, acceptedAt, startedAt, pickupOpenedAt, pickupVerifiedAt, pickupAppointmentAt;
        public Reply reply;
        public boolean disputeOpen, needsWitness;
        public Attendance attendance;
        public UUID beforeEvidenceId, afterEvidenceId, attendanceEvidenceId, approvedBy, pickupStaffId, pickupAssignedStaffId;
        public Instant approvedAt;
        public Instant reviewDueAt, reviewEscalatedAt, businessReviewDueAt;
        public String reasonNote, customerNote, report, plannedHandover, managerResponseNote;
        public Instant managerRespondedAt;
        public List<UUID> customerAttachmentIds = new ArrayList<>();
        public List<UUID> managerAttachmentIds = new ArrayList<>();
        public List<CommunicationEntry> communications = new ArrayList<>();
        public List<UUID> attempts = new ArrayList<>();
        public List<UUID> pickupAttempts = new ArrayList<>();
        public Map<String, Item> items = new LinkedHashMap<>();
        public Map<UUID, List<String>> itemBatches = new LinkedHashMap<>();
        public List<HistoryEvent> history = new ArrayList<>();
    }

    /** Called with the rental locked by the originating scheduler. */
    public boolean detect(Rental rental, ReturnCase source, Reason reason, Instant deadline, Instant now, boolean ignoredReview) {
        return openRecovery(rental, source, reason, deadline, now) != null;
    }

    @Transactional
    public UUID openConfirmedProhibitedGoodsRecovery(ActorPrincipal actor, UUID rentalId) {
        if (!actor.hasRole(RoleCode.MANAGER) || !actor.hasPermission(SystemPermission.MANAGE_STAFF_TASKS))
            throw ApiExceptions.forbidden("Cần quyền phân công thu hồi tại cơ sở.");
        var rental = rentals.findByIdForUpdate(rentalId)
            .filter(record -> scopes.canAccessFacility(actor, record.getFacility().getId(), FacilityScopeLevel.MANAGE))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thuê."));
        if (rental.getStatus() != RentalStatus.active)
            throw ApiExceptions.conflict("Chỉ chuyển thu hồi khi hồ sơ đang thuê.");
        var now = Instant.now();
        var recovery = openRecovery(rental, null, Reason.CONFIRMED_PROHIBITED_GOODS, now, now);
        if (recovery == null)
            throw ApiExceptions.conflict("Hồ sơ đang có luồng thu hồi hoặc chưa đủ điều kiện mở thu hồi.");
        return recovery.getId();
    }

    private ViolationRecovery openRecovery(Rental rental, ReturnCase source, Reason reason, Instant deadline, Instant now) {
        if (deadline == null || now.isBefore(deadline) || rental.getActualReturnedAt() != null
            || rental.getCompletedAt() != null || rental.getInventoryEscalatedAt() != null) return null;
        if (recoveries.findByRental_IdOrderByCreatedAtDesc(rental.getId()).stream().anyMatch(v ->
            !terminal(v) || v.getReason() == reason && v.getSourceDeadlineAt().equals(deadline))) return null;
        if (tasks.findByRental_IdAndTaskTypeAndStatusIn(rental.getId(), MaintenanceTaskType.maintenance,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)).stream()
            .anyMatch(task -> task.getRecoveryType() != MaintenanceRecoveryType.NONE)) return null;
        var v = new ViolationRecovery();
        v.setRental(rental);
        v.setSourceReturnId(source == null ? null : source.getId());
        v.setReason(reason);
        v.setSourceDeadlineAt(deadline);
        if (!reviewEvidence.read(v, now).openingAllowed()) return null;
        activate(v, now);
        var d = new Details();
        d.history.add(new HistoryEvent("DETECT", "Hệ thống", now, v.getStage(), reason.name()));
        save(v, d);
        recoveries.saveAndFlush(v);
        notifyCustomerSchedule(v, d, now, "Thông báo thu hồi kho");
        save(v, d);
        notifyCoordinationRequired(v);
        return v;
    }

    public boolean blocksLegacyReturn(UUID rentalId) {
        return recoveries.findByRental_IdOrderByCreatedAtDesc(rentalId).stream()
            .anyMatch(v -> v.getRequiredAt() != null && v.getStage() != Stage.CANCELLED);
    }

    @Transactional(readOnly = true)
    public List<Entry> list(ActorPrincipal actor, int page, String sort) {
        if (sort.equals("priority")) return list(actor, page);
        if (!java.util.Set.of("createdAt,desc", "createdAt,asc").contains(sort))
            throw ApiExceptions.validation("Thứ tự sắp xếp không hợp lệ.", null);
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ nhân viên được xem danh sách công việc được phân công.");
        var paging = PageRequest.of(Math.max(0, page), 30,
            org.springframework.data.domain.Sort.by(sort.endsWith("asc") ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC, "createdAt").and(org.springframework.data.domain.Sort.by("id")));
        return recoveries.findByTask_AssignedTo_Id(actor.userId(), paging).stream()
            .filter(v -> canRead(actor, v)).map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    @Transactional(readOnly = true)
    public List<Entry> list(ActorPrincipal actor, int page) {
        var paging = PageRequest.of(Math.max(0, page), 30);
        List<ViolationRecovery> rows;
        if (actor.hasRole(RoleCode.CUSTOMER)) rows = recoveries.findByRental_Customer_IdOrderByCreatedAtDesc(actor.userId(), paging);
        else if (actor.hasRole(RoleCode.STAFF)) rows = recoveries.findByTask_AssignedTo_IdOrderByCreatedAtDesc(actor.userId(), paging);
        else if (actor.hasAnyRole(RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            rows = operationsQueue(actor, false, paging);
        } else throw ApiExceptions.forbidden("Vai trò không có quyền xem thu hồi.");
        return rows.stream().filter(v -> canRead(actor, v)).map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    @Transactional(readOnly = true)
    public com.storagehub.common.api.PageResponse<Entry> customerPage(ActorPrincipal actor, int page, int pageSize, String correlationId) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) throw ApiExceptions.forbidden("Chỉ khách hàng được xem danh sách thu hồi của mình.");
        var now = Instant.now();
        var result = recoveries.customerPage(actor.userId(), PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, pageSize))))
            .map(v -> view(actor, v, details(v), now));
        return com.storagehub.common.api.PageResponse.from(result, correlationId);
    }

    @Transactional(readOnly = true)
    public Entry get(ActorPrincipal actor, UUID id) {
        if (!actor.hasAnyRole(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Vai trò không có quyền xem thu hồi.");
        var v = recoveries.findById(id).filter(entry -> canRead(actor, entry))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        return view(actor, v, details(v), Instant.now());
    }

    @Transactional(readOnly = true)
    public RecoveryReviewService.Preparation preparation(ActorPrincipal actor, UUID id) {
        if (!actor.hasAnyRole(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Bạn không có quyền xem dữ liệu chuẩn bị thu hồi.");
        var recovery = recoveries.findById(id).filter(entry -> canRead(actor, entry))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        return actor.hasRole(RoleCode.CUSTOMER) ? reviewEvidence.customerPreparation(recovery) : reviewEvidence.preparation(recovery);
    }

    @Transactional(readOnly = true)
    public List<Entry> listActiveOperations(ActorPrincipal actor, int page) {
        if (!actor.hasAnyRole(RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Vai trò không có quyền điều phối thu hồi.");
        var paging = PageRequest.of(Math.max(0, page), 30);
        var rows = operationsQueue(actor, true, paging);
        return rows.stream().filter(v -> canRead(actor, v)).map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    private List<ViolationRecovery> operationsQueue(ActorPrincipal actor, boolean active, org.springframework.data.domain.Pageable page) {
        boolean global = scopes.hasGlobalScope(actor);
        if (!global && actor.facilityScopes().isEmpty()) return List.of();
        var facilities = actor.facilityScopes().isEmpty() ? List.of(new UUID(0, 0)) : actor.facilityScopes().keySet();
        return recoveries.operationsQueue(global, facilities, active, actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN), page);
    }

    @Transactional(readOnly = true)
    public List<Entry> listEscalatedOperations(ActorPrincipal actor, int page) {
        if (!actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Vai trò không có quyền xử lý hồ sơ chuyển lên chủ doanh nghiệp.");
        var paging = PageRequest.of(Math.max(0, page), 30);
        var rows = scopes.hasGlobalScope(actor)
            ? recoveries.findByEscalatedTrueAndStageInOrderByCreatedAtDesc(MANAGER_STAGES, paging)
            : actor.facilityScopes().isEmpty() ? List.<ViolationRecovery>of()
            : recoveries.findByRental_Facility_IdInAndEscalatedTrueAndStageInOrderByCreatedAtDesc(
                actor.facilityScopes().keySet(), MANAGER_STAGES, paging);
        return rows.stream().filter(v -> canRead(actor, v))
            .map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    @Transactional(readOnly = true)
    public List<Entry> listForReturn(ActorPrincipal actor, UUID returnId) {
        if (!actor.hasAnyRole(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Vai trò không có quyền xem thu hồi.");
        return recoveries.findBySourceReturnIdOrderByCreatedAtDesc(returnId).stream()
            .filter(v -> v.getStage() != Stage.CANCELLED && canRead(actor, v))
            .map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Entry execute(ActorPrincipal actor, UUID id, Command command) {
        var source = recoveries.findById(id).filter(v -> canRead(actor, v))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        rentals.findByIdForUpdate(source.getRental().getId()).orElseThrow();
        var v = recoveries.lock(id).orElseThrow();
        em.refresh(v, LockModeType.PESSIMISTIC_WRITE);
        if (!canRead(actor, v)) throw ApiExceptions.forbidden("Bạn không còn được phân công hồ sơ này.");
        var d = details(v);
        var now = Instant.now();
        upgradeLegacyFlow(v, d, now);
        if (command.action() == Action.SAVE_ITEM && command.attemptId() != null
            && d.itemBatches.containsKey(command.attemptId()) && actor.hasRole(RoleCode.STAFF)
            && actor.hasPermission(SystemPermission.PROCESS_RETURNS) && isAssigned(v, actor)) return view(actor, v, d, now);
        if ((command.action() == Action.VERIFY || command.action() == Action.VERIFY_PICKUP)
            && actor.hasRole(RoleCode.STAFF) && actor.hasPermission(SystemPermission.PROCESS_RETURNS)
            && isAssigned(v, actor) && command.attemptId() != null
            && (command.action() == Action.VERIFY ? d.attempts : d.pickupAttempts).contains(command.attemptId()))
            return view(actor, v, d, now);
        if (command.action() == Action.COMPLETE && v.getStage() == Stage.COMPLETED
            && actor.userId().equals(v.getCompletedBy())) return view(actor, v, d, now);
        if (!allowed(actor, v, d, now).contains(command.action()))
            throw ApiExceptions.conflict("Thao tác chưa đủ điều kiện hoặc hồ sơ đã chuyển bước.");
        var before = view(actor, v, d, now);
        apply(actor, v, d, command, now);
        if (!terminal(v)) notifyCoordinationRequired(v);
        String actorName = users.findById(actor.userId()).map(User::getFullName)
            .orElse(actor.roles().stream().findFirst().map(Enum::name).orElse("Hệ thống"));
        var historyNote = command.action() == Action.RECORD_LIQUIDATION
            ? (command.itemCodes() == null ? normalize(command.itemCode()) : command.itemCodes().size() + " kiện") + " · " + clean(command.note())
            : command.action() == Action.VERIFY || command.action() == Action.VERIFY_PICKUP
                ? verification(v, d, command.action() == Action.VERIFY_PICKUP, now).message() : clean(command.note());
        d.history.add(new HistoryEvent(command.action().name(), actorName, now, v.getStage(), historyNote));
        recordCommunication(actor, actorName, d, command, now);
        save(v, d);
        var after = view(actor, v, d, now);
        audit.recordMutation("RECOVERY_" + command.action(), "violation_recovery", id, v.getRental().getFacility().getId(),
            before, Map.of("entry", after, "note", clean(command.note())));
        return after;
    }

    private void apply(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        switch (c.action()) {
            case CONFIRM_VIOLATION, ACCEPT, DECLINE, APPROVE -> throw ApiExceptions.conflict("Luồng thu hồi không còn bước tiếp nhận hoặc phê duyệt thủ công.");
            case RESCHEDULE -> throw ApiExceptions.conflict("Thời gian thu hồi do hệ thống ấn định, không thể đổi lịch thủ công.");
            case ASSIGN -> assign(actor, v, d, c, now);
            case WILL_ATTEND, CANNOT_ATTEND -> {
                Reply target = Reply.valueOf(c.action().name());
                if (d.reply == target) {
                    throw ApiExceptions.conflict(target == Reply.WILL_ATTEND
                        ? "Bạn đã xác nhận sẽ có mặt. Chỉ có thể chọn lại sau khi chuyển sang không thể có mặt."
                        : "Bạn đã báo không thể có mặt. Chỉ có thể chọn lại sau khi chuyển sang sẽ có mặt.");
                }
                d.reply = target;
                d.customerNote = clean(c.note());
                var uploaded = attachments(v, actor, c.attachmentIds());
                if (!uploaded.isEmpty()) d.customerAttachmentIds.addAll(uploaded);
            }
            case DISPUTE -> {
                d.customerNote = required(c.note(), "nội dung phản đối");
                d.reply = Reply.DISPUTED;
                var uploaded = attachments(v, actor, c.attachmentIds());
                if (!uploaded.isEmpty()) d.customerAttachmentIds.addAll(uploaded);
                if (terminal(v)) {
                    v.setFinancePending(true);
                    notifyBusiness(v, "Khách gửi khiếu nại sau thu hồi");
                } else if (!d.disputeOpen) {
                    d.disputeOpen = true;
                    advance(v, Stage.BLOCKED, now.plus(Duration.ofHours(6)));
                    notifyOperations(v, "Khách phản đối. Cần tạm dừng và rà soát");
                }
            }
            case RECORD_PRESENT, RECORD_ABSENT -> attendance(actor, v, d, c, now);
            case VERIFY, VERIFY_PICKUP -> verify(v, d, c, now);
            case START -> start(actor, v, d, c, now);
            case SAVE_ITEM -> saveItem(actor, v, d, c, now);
            case BLOCK -> {
                block(v, d, required(c.note(), "lý do tạm dừng"), now);
                var uploaded = attachments(v, actor, c.attachmentIds());
                if (!uploaded.isEmpty()) d.managerAttachmentIds.addAll(uploaded);
            }
            case RESUME -> resume(actor, v, d, c, now);
            case CANCEL -> cancel(actor, v, d, c, now);
            case CLOSE_REMEDIATION -> closeRemediation(actor, v, d, c, now);
            case COMPLETE -> complete(actor, v, d, c, now);
            case SCHEDULE_PICKUP -> {
                if (c.appointmentAt() == null) {
                    throw ApiExceptions.validation("Vui lòng chọn ngày giờ hẹn nhận hàng.", null);
                }
                if (c.appointmentAt().isBefore(now.minusSeconds(60))) {
                    throw ApiExceptions.validation("Không thể chọn lịch hẹn nhận hàng trong quá khứ.", null);
                }
                if (v.getPickupDueAt() != null && c.appointmentAt().isAfter(v.getPickupDueAt())) {
                    throw ApiExceptions.deadlineExpired("Lịch hẹn nhận hàng phải nằm trong thời hạn 7 ngày lưu tạm.");
                }
                d.pickupAppointmentAt = c.appointmentAt();
                d.pickupAssignedStaffId = null;
                d.pickupOpenedAt = null;
                d.pickupVerifiedAt = null;
                d.pickupAttempts.clear();
                notifyOperations(v, "Khách đã đặt lịch hẹn nhận hàng lưu tạm");
            }
            case ASSIGN_PICKUP -> {
                if (d.pickupAppointmentAt == null) {
                    throw ApiExceptions.invalidState("Khách hàng chưa đặt lịch hẹn nhận hàng lưu tạm.");
                }
                staffAvailability.lock(Collections.singletonList(c.staffId()));
                var pickupStaff = availableStaffAt(c.staffId(), v, d.pickupAppointmentAt, d.pickupAppointmentAt.plus(WINDOW));
                v.getTask().setAssignedTo(pickupStaff);
                d.pickupAssignedStaffId = pickupStaff.getId();
                d.pickupOpenedAt = null;
                d.pickupVerifiedAt = null;
                d.pickupAttempts.clear();
                notifyStaff(v, "Bạn được phân công bàn giao tài sản lưu tạm");
            }
            case OPEN_PICKUP -> {
                d.pickupOpenedAt = now;
                d.pickupVerifiedAt = null;
                d.pickupStaffId = actor.userId();
                d.pickupAttempts.clear();
            }
            case RELEASE_ITEM -> releaseItem(actor, v, d, c, now);
            case RECORD_LIQUIDATION -> recordLiquidation(actor, v, d, c, now);
            case FOLLOW_UP_PICKUP -> {
                d.reasonNote = required(c.note(), "kết quả rà soát hàng lưu tạm");
                v.setPickupReviewAt(future(c.appointmentAt(), now));
                v.setPickupEscalated(false);
                d.pickupOpenedAt = null;
                d.pickupVerifiedAt = null;
                d.pickupAttempts.clear();
            }
            case REQUEST_FINANCE -> {
                d.customerNote = required(c.note(), "nội dung cần đối soát");
                v.setFinancePending(true);
                v.setFinanceClosed(false);
                notifyBusiness(v, "Cần đối soát cọc hoặc công nợ sau thu hồi");
            }
            case RESOLVE_FINANCE -> {
                d.reasonNote = required(c.note(), "kết luận tài chính hoặc mã hồ sơ tiếp nhận");
                v.setFinancePending(false);
                v.setFinanceClosed(true);
            }
        }
    }

    private void activate(ViolationRecovery v, Instant now) {
        var rental = v.getRental();
        v.setPolicyVersion("RECOVERY_V2");
        v.setRequiredAt(now);
        v.setScheduledAt(now.plus(Duration.ofHours(24)));
        v.setDeadlineAt(v.getScheduledAt().plus(Duration.ofHours(48)));
        v.setPreviousRentalStatus(rental.getStatus());
        if (v.getSourceReturnId() != null) {
            var source = returns.findByIdForUpdate(v.getSourceReturnId()).orElseThrow();
            v.setPreviousReturnStatus(source.getStatus());
            source.setStatus(ReturnCaseStatus.withdrawn_for_recovery);
        }
        rental.setInventoryEscalatedAt(now);
        rental.setStatus(RentalStatus.closing);
        advance(v, Stage.AWAITING_ASSIGNMENT, now.plus(Duration.ofHours(2)));
    }

    private void validateActivation(ViolationRecovery v, Instant now) {
        var r = v.getRental();
        if (r.getCompletedAt() != null || r.getActualReturnedAt() != null || r.getInventoryEscalatedAt() != null
            || !List.of(RentalStatus.setup, RentalStatus.active, RentalStatus.return_requested).contains(r.getStatus()))
            throw ApiExceptions.conflict("Hồ sơ đã thay đổi. Cần kiểm tra lại trước khi xác định vi phạm.");
        Instant current = switch (v.getReason()) {
            case SETUP_OVERDUE -> r.getMoveInCompletedAt() == null ? r.getSetupCompletionDeadlineAt() : null;
            case INVENTORY_TIMEOUT -> r.isInventoryPending() && r.getInventoryResultPublishedAt() == null
                ? InventoryDeadlinePolicy.completionDeadline(r) : null;
            case INVENTORY_NO_SHOW, INVENTORY_IDENTITY_FAILED ->
                r.isInventoryPending() && r.getInventoryResultPublishedAt() == null ? r.getInventoryFinalDeadlineAt() : null;
            case INVENTORY_CONFIRMATION_OVERDUE -> {
                var checkIn = em.createQuery("select c from CheckIn c where c.reservation.id = :reservationId", CheckIn.class)
                    .setParameter("reservationId", r.getReservation().getId()).getResultStream().findFirst().orElse(null);
                yield checkIn != null && checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.PENDING_CUSTOMER
                    && r.getInventoryResultPublishedAt() != null ? r.getInventoryResultPublishedAt().plus(Duration.ofHours(48)) : null;
            }
            case REMOVAL_OVERDUE, HANDOVER_FOLLOW_UP -> r.getInventoryRemovalDeadlineAt();
            case CONFIRMED_PROHIBITED_GOODS -> v.getSourceDeadlineAt();
        };
        if (current == null || now.isBefore(current))
            throw ApiExceptions.conflict("Mốc nguồn đã thay đổi hoặc chưa đến hạn.");
        var review = reviewEvidence.read(v, now);
        if (!review.openingAllowed()) throw ApiExceptions.conflict(review.blockingReason());
        if (v.getSourceReturnId() != null) {
            var rc = returns.findByIdForUpdate(v.getSourceReturnId()).orElseThrow();
            if (rc.getInspectedAt() != null || rc.getReadyForInspectionAt() != null || ReturnAttendancePolicy.protectsFromRecovery(rc))
                throw ApiExceptions.conflict("Hồ sơ đã bàn giao hoặc đang được bảo vệ bởi phiên tiếp nhận.");
        }
    }

    private void assign(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        staffAvailability.lock(Collections.singletonList(c.staffId()));
        var staff = availableStaffAt(c.staffId(), v, v.getScheduledAt(), v.getDeadlineAt());
        var task = v.getTask();
        if (task == null) {
            var r = v.getRental();
            task = new MaintenanceTask();
            task.setRental(r);
            task.setFacility(r.getFacility());
            task.setStorageUnit(r.getStorageUnit());
            task.setReportedBy(users.findById(actor.userId()).orElseThrow());
            task.setTitle("Thu hồi kho do vi phạm");
            task.setReason("Thực hiện theo lịch thu hồi đã được hệ thống thông báo cho khách.");
            task.setPriority(TaskPriority.high);
            task.setRecoveryType(MaintenanceRecoveryType.VIOLATION);
            task.setDeadlineAt(v.getDeadlineAt());
            task.setDueAt(v.getDeadlineAt().atZone(BusinessTime.ZONE).toLocalDate());
            v.setTask(tasks.saveAndFlush(task));
        }
        if (task.getAssignedTo() != null && !staff.getId().equals(task.getAssignedTo().getId()))
            task.setRecoveryWitness(null);
        task.setAssignedTo(staff);
        if (v.getStage() != Stage.BLOCKED) advance(v, Stage.AWAITING_ATTENDANCE, v.getDeadlineAt());
        notifyStaff(v, "Bạn được phân công nhiệm vụ thu hồi");
        notifyCustomer(v, "Đã phân công nhân viên thu hồi");
    }

    private void upgradeLegacyFlow(ViolationRecovery v, Details d, Instant now) {
        // Older identity failures paused recovery itself. Only remove that specific automatic pause.
        if (v.getStage() == Stage.BLOCKED && !d.disputeOpen && d.verifiedAt == null
            && d.attempts.size() >= VERIFICATION_LIMIT && "Đã hết số lượt xác minh danh tính.".equals(d.reasonNote)
            && !d.history.isEmpty() && "VERIFY".equals(d.history.getLast().action())) {
            d.needsWitness = true;
            d.reasonNote = "Xác minh chưa thành công. Tiếp tục thu hồi và lưu tạm; chưa được bàn giao cho khách.";
            advance(v, d.startedAt != null ? Stage.IN_PROGRESS
                : v.getTask() == null || v.getTask().getAssignedTo() == null ? Stage.AWAITING_ASSIGNMENT
                : Stage.AWAITING_ATTENDANCE, v.getDeadlineAt());
            d.history.add(new HistoryEvent("IDENTITY_STORAGE", "Hệ thống", now, v.getStage(), d.reasonNote));
            notifyStaff(v, "Tiếp tục thu hồi và lưu tạm theo lịch; chưa được giao hàng cho khách");
        }
        if (terminal(v) || "RECOVERY_V2".equals(v.getPolicyVersion())) return;
        if (v.getStage() == Stage.REVIEW_REQUIRED && v.getRequiredAt() == null) {
            try {
                validateActivation(v, now);
            } catch (com.storagehub.common.api.ApiException changed) {
                d.reasonNote = "Không mở thu hồi: " + changed.getMessage();
                advance(v, Stage.CANCELLED, null);
                v.setResponseCheckAt(null);
                v.setPolicyVersion("RECOVERY_V2");
                return;
            }
            activate(v, now);
            notifyCustomerSchedule(v, d, now, "Thông báo thu hồi kho");
        } else if (List.of(Stage.AWAITING_ACCEPTANCE, Stage.AWAITING_APPROVAL, Stage.APPROVED).contains(v.getStage())) {
            advance(v, d.disputeOpen ? Stage.BLOCKED : d.startedAt != null ? Stage.IN_PROGRESS
                : v.getTask() == null || v.getTask().getAssignedTo() == null ? Stage.AWAITING_ASSIGNMENT
                : Stage.AWAITING_ATTENDANCE, d.disputeOpen ? working(now, 1) : v.getDeadlineAt());
        } else if (v.getStage() == Stage.AWAITING_ATTENDANCE) {
            v.setActionDueAt(v.getDeadlineAt());
        }
        v.setPolicyVersion("RECOVERY_V2");
        d.history.add(new HistoryEvent("AUTOMATIC_FLOW", "Hệ thống", now, v.getStage(),
            "Chuyển sang thu hồi theo lịch, không yêu cầu tiếp nhận hoặc phê duyệt thủ công."));
    }

    private void attendance(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        boolean present = c.action() == Action.RECORD_PRESENT;
        if (present && d.reply == Reply.CANNOT_ATTEND) {
            throw ApiExceptions.conflict("Khách hàng đã báo không thể có mặt, không thể ghi nhận có mặt.");
        }
        if (!present && c.appointmentAt() != null
            && (c.appointmentAt().isAfter(now) || c.appointmentAt().isBefore(v.getRequiredAt())))
            throw ApiExceptions.validation("Giờ ghi nhận khách không hợp lệ.", null);
        d.attendanceEvidenceId = evidence(v, actor, c.evidenceId());
        d.attendance = present ? Attendance.PRESENT : Attendance.ABSENT;
        d.arrivedAt = present ? now : null;
        d.verifiedAt = null;
        d.reasonNote = clean(c.note());
        if (d.startedAt != null || d.disputeOpen) {
            block(v, d, "Khách có mặt khi hồ sơ đang tạm dừng hoặc đã bắt đầu thu hồi.", now);
        } else {
            d.needsWitness |= !present;
            advance(v, Stage.AWAITING_ATTENDANCE, v.getDeadlineAt());
            if (present && c.document() != null && !normalize(v.getRental().getReservation().getIdentityDocumentNumber()).isEmpty()) {
                verify(v, d, c, now);
            }
        }
    }

    private void verify(ViolationRecovery v, Details d, Command c, Instant now) {
        boolean pickup = c.action() == Action.VERIFY_PICKUP;
        var attempts = pickup ? d.pickupAttempts : d.attempts;
        if (c.attemptId() == null) throw ApiExceptions.validation("Thiếu mã lần đối chiếu.", null);
        if (attempts.contains(c.attemptId())) return;
        String expected = normalize(v.getRental().getReservation().getIdentityDocumentNumber());
        String actual = normalize(c.document());
        if (expected.isEmpty()) throw ApiExceptions.conflict("Hồ sơ thiếu giấy tờ đối chiếu. Chỉ được thu hồi và lưu tạm, chưa được giao hàng cho khách.");
        if (!com.storagehub.validation.IdentityDocumentPolicy.isValid(actual)
            && !actual.matches("(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6,20}"))
            throw ApiExceptions.validation("Giấy tờ không đúng định dạng.", null);
        attempts.add(c.attemptId());
        if (expected.equals(actual)) {
            if (pickup) d.pickupVerifiedAt = now;
            else {
                d.verifiedAt = now;
                if (v.getStage() != Stage.BLOCKED) advance(v, Stage.AWAITING_ATTENDANCE, v.getDeadlineAt());
            }
        } else if (attempts.size() >= VERIFICATION_LIMIT && pickup) {
            v.setPickupReviewAt(now);
            notifyOperations(v, "Cần đối soát giấy tờ nhận tài sản");
        }
    }

    private void start(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        // Unverified customers cannot receive goods; recovery continues with a witness and temporary storage.
        boolean needsWitness = needsWitness(d, now);
        var participants = new ArrayList<UUID>();
        participants.add(actor.userId());
        UUID witnessId = c.witnessId() != null ? c.witnessId()
            : v.getTask().getRecoveryWitness() == null ? null : v.getTask().getRecoveryWitness().getId();
        if (needsWitness) participants.add(witnessId);
        staffAvailability.lock(participants);
        availableStaffAt(actor.userId(), v, now, later(now.plus(WINDOW), v.getDeadlineAt()));
        if (needsWitness) {
            var witness = availableStaffAt(witnessId, v, now, later(now.plus(WINDOW), v.getDeadlineAt()));
            if (actor.userId().equals(witness.getId())) throw ApiExceptions.validation("Cần nhân chứng độc lập.", null);
            v.getTask().setRecoveryWitness(witness);
            d.needsWitness = true;
        }
        if (d.startedAt == null) {
            d.beforeEvidenceId = evidence(v, actor, c.evidenceId());
            d.plannedHandover = required(c.location(), "nơi lưu tạm dự kiến hoặc người nhận");
            d.startedAt = now;
            v.getTask().setStartedAt(now);
            v.getTask().setStatus(MaintenanceTaskStatus.in_progress);
        }
        advance(v, Stage.IN_PROGRESS, v.getDeadlineAt().isAfter(now) ? v.getDeadlineAt() : working(now, 2));
    }

    private void saveItem(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        int quantity = c.quantity() == null ? 1 : c.quantity();
        if (quantity < 1 || quantity > 1000) throw ApiExceptions.validation("Số kiện phải từ 1 đến 1.000.", null);
        String prefix = c.itemCode() == null || c.itemCode().isBlank()
            ? "KH-" + (c.attemptId() == null ? UUID.randomUUID() : c.attemptId()).toString().replace("-", "").toUpperCase(Locale.ROOT)
            : normalize(c.itemCode());
        var codes = new ArrayList<String>();
        for (int i = 1; i <= quantity; i++) {
            String code = quantity == 1 ? prefix : prefix + "-" + i;
            if (code.length() > 80) throw ApiExceptions.validation("Mã lô hàng quá dài.", null);
            if (d.items.containsKey(code)) throw ApiExceptions.conflict("Mã kiện đã được ghi nhận.");
            codes.add(code);
        }
        String disposition = c.disposition();
        if (!"STORED".equals(disposition) && !"HANDED_OVER".equals(disposition))
            throw ApiExceptions.validation("Cần chọn giao khách hoặc lưu tạm.", null);
        boolean handover = "HANDED_OVER".equals(disposition);
        if (handover && !within(d.arrivedAt, now, d.verifiedAt))
            throw ApiExceptions.conflict("Cần xác minh quyền nhận tài sản còn hiệu lực.");
        String name = required(c.itemName(), "loại hàng"), condition = required(c.condition(), "tình trạng");
        String location = required(c.location(), "nơi lưu tạm hoặc người nhận");
        UUID photo = evidence(v, actor, c.evidenceId());
        for (var code : codes) d.items.put(code, new Item(code, name, condition, location, photo,
            disposition, handover ? now : null, null, null));
        if (c.attemptId() != null) d.itemBatches.put(c.attemptId(), codes);
    }

    private void resume(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        boolean wasDispute = d.disputeOpen;
        d.reasonNote = required(c.note(), "kết luận xử lý ngoại lệ");
        d.managerResponseNote = d.reasonNote;
        d.managerRespondedAt = now;
        var uploaded = attachments(v, actor, c.attachmentIds());
        if (!uploaded.isEmpty()) d.managerAttachmentIds.addAll(uploaded);
        if (v.getTask() != null && v.getTask().getAssignedTo() != null) {
            var participants = new ArrayList<UUID>();
            participants.add(v.getTask().getAssignedTo().getId());
            if (v.getTask().getRecoveryWitness() != null) participants.add(v.getTask().getRecoveryWitness().getId());
            staffAvailability.lock(participants);
            for (var participant : participants)
                availableStaffAt(participant, v, later(now, v.getScheduledAt()), later(now.plus(WINDOW), v.getDeadlineAt()));
        }
        if (d.disputeOpen) d.needsWitness = true;
        d.disputeOpen = false;
        advance(v, d.startedAt != null ? Stage.IN_PROGRESS
            : v.getTask() == null || v.getTask().getAssignedTo() == null ? Stage.AWAITING_ASSIGNMENT : Stage.AWAITING_ATTENDANCE,
            v.getDeadlineAt());
        if (d.attendance == Attendance.PRESENT && !within(d.arrivedAt, now, d.verifiedAt)) {
            d.verifiedAt = null;
        }
        if (v.getRequiredAt() != null) {
            notifyCustomerWithDetail(
                v,
                wasDispute ? "Quản lý đã phản hồi phản đối và tiếp tục thu hồi" : "Hồ sơ thu hồi được tiếp tục xử lý",
                d.managerResponseNote
            );
        }
    }

    private void cancel(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        if (d.startedAt != null) {
            throw ApiExceptions.invalidState("Không thể hủy thu hồi khi đã bắt đầu thu hồi thực tế.");
        }
        if (v.getRequiredAt() != null && now.isAfter(v.getRequiredAt().plus(Duration.ofHours(12)))) {
            throw ApiExceptions.deadlineExpired("Chỉ có thể hủy thu hồi trong 12 giờ đầu kể từ khi phát sinh yêu cầu.");
        }
        d.reasonNote = required(c.note(), "căn cứ hủy thu hồi");
        d.managerResponseNote = d.reasonNote;
        d.managerRespondedAt = now;
        var uploaded = attachments(v, actor, c.attachmentIds());
        if (!uploaded.isEmpty()) d.managerAttachmentIds.addAll(uploaded);
        if (v.getRequiredAt() != null) {
            var rental = v.getRental();
            var unit = rental.getStorageUnit();
            em.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
            if (unit.getStatus() != StorageUnitStatus.occupied
                || rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId())
                || assignments.existsOtherActiveAssignment(unit.getId(), rental.getReservation().getId())) {
                throw ApiExceptions.conflict("Gian kho không còn nguyên trạng để khôi phục.");
            }
            restoreRental(v);
        }
        if (v.getTask() != null) v.getTask().setStatus(MaintenanceTaskStatus.cancelled);
        advance(v, Stage.CANCELLED, null);
        v.setResponseCheckAt(null);
        if (v.getRequiredAt() != null) {
            notifyCustomerWithDetail(v, "Yêu cầu thu hồi đã được hủy", d.managerResponseNote);
        }
    }

    private void closeRemediation(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        d.reasonNote = required(c.note(), "kết luận khắc phục, tình trạng gian và tài sản");
        d.managerResponseNote = d.reasonNote;
        d.managerRespondedAt = now;
        var uploaded = attachments(v, actor, c.attachmentIds());
        if (!uploaded.isEmpty()) d.managerAttachmentIds.addAll(uploaded);
        var rental = v.getRental();
        var unit = rental.getStorageUnit();
        em.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
        if ("RESTORED".equals(c.disposition())) {
            if (rental.getStatus() != RentalStatus.closing || unit.getStatus() != StorageUnitStatus.occupied
                || rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId())
                || assignments.existsOtherActiveAssignment(unit.getId(), rental.getReservation().getId()))
                throw ApiExceptions.conflict("Chưa thể khôi phục quyền sử dụng gian theo hiện trạng.");
            restoreRental(v);
        } else if (!"ALREADY_RESOLVED".equals(c.disposition()) || rental.getStatus() != RentalStatus.completed
            || rental.getActualReturnedAt() == null) {
            throw ApiExceptions.conflict("Cần khôi phục gian hợp lệ hoặc xác nhận hồ sơ thuê đã được xử lý xong.");
        }
        v.getTask().setStatus(MaintenanceTaskStatus.cancelled);
        advance(v, Stage.CANCELLED, null);
        v.setResponseCheckAt(null);
        startPickupFollowUp(v, d, now);
        notifyCustomer(v, "Chủ doanh nghiệp đã xử lý yêu cầu thu hồi bị hủy. Vui lòng liên hệ cơ sở.");
    }

    private void restoreRental(ViolationRecovery v) {
        v.getRental().setInventoryEscalatedAt(null);
        v.getRental().setStatus(v.getPreviousRentalStatus());
        if (v.getSourceReturnId() != null && v.getPreviousReturnStatus() != null) {
            var source = returns.findByIdForUpdate(v.getSourceReturnId()).orElseThrow();
            if (source.getStatus() == ReturnCaseStatus.withdrawn_for_recovery) source.setStatus(v.getPreviousReturnStatus());
        }
    }

    private void complete(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        d.afterEvidenceId = evidence(v, actor, c.evidenceId());
        if (d.afterEvidenceId.equals(d.beforeEvidenceId))
            throw ApiExceptions.validation("Cần ảnh xác nhận gian trống riêng.", null);
        d.report = required(c.note(), "biên bản xác nhận kho trống và xử lý khóa");
        if (!"VACATED".equals(c.disposition())) throw ApiExceptions.validation("Cần xác nhận gian đã trống.", null);
        var rental = v.getRental();
        var unit = rental.getStorageUnit();
        access.revokeForRental(actor, rental, "Hoàn tất thu hồi kho do vi phạm");
        em.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
        if (rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId())
            || assignments.existsOtherActiveAssignment(unit.getId(), rental.getReservation().getId()))
            throw ApiExceptions.conflict("Gian đã có hồ sơ hoặc phân kho khác. Cần chủ doanh nghiệp khắc phục.");
        var task = v.getTask();
        task.setStatus(MaintenanceTaskStatus.completed);
        task.setCompletedAt(now);
        task.setResultReport(d.report);
        var maintenance = new MaintenanceTask();
        maintenance.setFacility(rental.getFacility());
        maintenance.setStorageUnit(unit);
        maintenance.setRental(rental);
        maintenance.setRecoveryType(MaintenanceRecoveryType.VIOLATION_UNIT_READINESS);
        maintenance.setTitle("Kiểm tra gian sau thu hồi do vi phạm");
        maintenance.setReason("Kiểm tra gian trống, vệ sinh, khóa và quyền truy cập cũ trước khi mở lại.");
        maintenance.setPriority(TaskPriority.high);
        maintenance.setDeadlineAt(working(now, 24));
        maintenance.setDueAt(maintenance.getDeadlineAt().atZone(BusinessTime.ZONE).toLocalDate());
        tasks.save(maintenance);
        assignments.findByReservation_IdAndStatus(rental.getReservation().getId(), UnitAssignmentStatus.ACTIVE)
            .filter(a -> unit.getId().equals(a.getStorageUnit().getId())).ifPresent(a -> a.setStatus(UnitAssignmentStatus.COMPLETED));
        rental.setActualReturnedAt(now);
        rental.setCompletedAt(now);
        rental.setStatus(RentalStatus.completed);
        rental.setEndReason(RentalEndReason.VIOLATION_RECOVERY);
        rental.setInventoryPending(false);
        unit.setStatus(StorageUnitStatus.maintenance);
        v.setCompletedAt(now);
        v.setCompletedBy(actor.userId());
        if (v.getOverdueAt() == null && now.isAfter(v.getDeadlineAt())) v.setOverdueAt(v.getDeadlineAt());
        startPickupFollowUp(v, d, now);
        advance(v, Stage.COMPLETED, null);
        v.setResponseCheckAt(null);
        notifyCustomer(v, hasStoredItems(d) ? "Đã thu hồi kho, có tài sản chờ nhận" : "Đã hoàn tất thu hồi kho");
        notifyOperations(v, "Gian kho cần kiểm tra sau thu hồi");
    }

    private void startPickupFollowUp(ViolationRecovery v, Details d, Instant now) {
        if (hasStoredItems(d)) {
            v.setPickupDueAt(now.atZone(BusinessTime.ZONE).plusDays(7).toInstant());
            v.setPickupReviewAt(v.getPickupDueAt().plus(Duration.ofHours(24)));
        }
    }

    private void releaseItem(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        var selected = selectedPendingItems(d, c);
        UUID photo = evidence(v, actor, c.evidenceId());
        for (var item : selected) d.items.put(item.code(), new Item(item.code(), item.name(), item.condition(), item.location(), item.evidenceId(),
            item.disposition(), now, photo, null));
        closePickupIfDone(v, d);
    }

    private void recordLiquidation(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        var selected = selectedPendingItems(d, c);
        if (!"LIQUIDATED".equals(c.disposition()))
            throw ApiExceptions.validation("Cần xác nhận đã xử lý thanh lý kiện hàng theo biên bản.", null);
        var liquidation = new Liquidation(now, actor.userId(),
            required(c.note(), "căn cứ, biên bản và kết quả xử lý thanh lý"), evidence(v, actor, c.evidenceId()));
        for (var item : selected) d.items.put(item.code(), new Item(item.code(), item.name(), item.condition(), item.location(), item.evidenceId(),
            item.disposition(), null, null, liquidation));
        closePickupIfDone(v, d);
        notifyCustomer(v, "Đã ghi nhận xử lý thanh lý tài sản lưu tạm");
    }

    private List<Item> selectedPendingItems(Details d, Command c) {
        var codes = c.itemCodes() == null ? List.of(normalize(required(c.itemCode(), "kiện cần xử lý")))
            : c.itemCodes().stream().map(value -> normalize(required(value, "mã kiện"))).toList();
        if (codes.isEmpty() || codes.size() > 1000 || new HashSet<>(codes).size() != codes.size())
            throw ApiExceptions.validation("Danh sách kiện cần xử lý không hợp lệ hoặc bị trùng.", null);
        var selected = new ArrayList<Item>();
        for (var code : codes) {
            var item = d.items.get(code);
            if (item == null || !isPendingItem(item))
                throw ApiExceptions.conflict("Một hoặc nhiều kiện không còn chờ nhận; hãy kiểm tra số lượng còn lại.");
            selected.add(item);
        }
        return selected;
    }

    private void closePickupIfDone(ViolationRecovery v, Details d) {
        if (!hasStoredItems(d)) {
            v.setPickupReviewAt(null);
            v.setPickupEscalated(false);
        }
    }

    @Transactional
    public void processDue(UUID id, Instant now) {
        var source = recoveries.findById(id).orElse(null);
        if (source == null) return;
        rentals.findByIdForUpdate(source.getRental().getId()).orElseThrow();
        var v = recoveries.lock(id).orElse(null);
        if (v == null) return;
        var d = details(v);
        upgradeLegacyFlow(v, d, now);
        if (v.getDeadlineAt() != null && now.isAfter(v.getDeadlineAt()) && !terminal(v) && v.getOverdueAt() == null) {
            v.setOverdueAt(v.getDeadlineAt());
            notifyOperations(v, "Nhiệm vụ thu hồi đã quá hạn hoàn tất");
            notifyBusiness(v, "Nhiệm vụ thu hồi đã quá hạn hoàn tất");
        }
        if (!terminal(v) && d.responseDueAt != null && !now.isBefore(d.responseDueAt)) {
            if (d.reply == null) {
                d.reply = Reply.NO_RESPONSE;
                notifyOperations(v, "Khách chưa phản hồi thông báo thu hồi");
            }
            v.setResponseCheckAt(null);
        }
        if (v.getActionDueAt() != null && !now.isBefore(v.getActionDueAt()) && !terminal(v)) {
            if (List.of(Stage.AWAITING_ATTENDANCE, Stage.IN_PROGRESS).contains(v.getStage())) {
                // An elapsed appointment is not an objection and does not pause Staff's work.
                v.setActionDueAt(null);
            } else {
                v.setEscalated(true);
                v.setActionDueAt(working(now, 4));
                notifyBusiness(v, "Cần xử lý hồ sơ thu hồi đang quá hạn");
            }
        }
        if (v.getPickupReviewAt() != null && !now.isBefore(v.getPickupReviewAt()) && !v.isPickupEscalated()) {
            v.setPickupEscalated(true);
            notifyBusiness(v, "Cần rà soát tài sản lưu tạm");
        }
        save(v, d);
        if (!terminal(v)) notifyCoordinationRequired(v);
    }

    private List<Action> allowed(ActorPrincipal actor, ViolationRecovery v, Details d, Instant now) {
        List<Action> actions = new ArrayList<>();
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            if (v.getRequiredAt() == null) return actions;
            if (!terminal(v) && d.responseDueAt != null && now.isBefore(d.responseDueAt)) {
                if (!d.disputeOpen) {
                    if (d.reply != Reply.WILL_ATTEND) actions.add(Action.WILL_ATTEND);
                    if (d.reply != Reply.CANNOT_ATTEND) actions.add(Action.CANNOT_ATTEND);
                }
                actions.add(Action.DISPUTE);
            }
            if (terminal(v) && hasStoredItems(d) && v.getPickupDueAt() != null && now.isBefore(v.getPickupDueAt())) {
                actions.add(Action.SCHEDULE_PICKUP);
            }
            return actions;
        }
        var facility = v.getRental().getFacility().getId();
        boolean manager = actor.hasAnyRole(RoleCode.MANAGER, RoleCode.ADMIN)
            && actor.hasPermission(SystemPermission.MANAGE_STAFF_TASKS)
            && scopes.canAccessFacility(actor, facility, FacilityScopeLevel.MANAGE);
        boolean business = actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN)
            && scopes.canAccessFacility(actor, facility, FacilityScopeLevel.MANAGE);
        if ((manager || business) && terminal(v) && hasStoredItems(d)) {
            actions.add(Action.FOLLOW_UP_PICKUP);
            if (d.pickupAppointmentAt != null) actions.add(Action.ASSIGN_PICKUP);
            if (business) actions.add(Action.RECORD_LIQUIDATION);
        }
        if (!terminal(v) && (manager || business && v.isEscalated())) {
            boolean withinCancelWindow = d.startedAt == null
                && (v.getRequiredAt() == null || !now.isAfter(v.getRequiredAt().plus(Duration.ofHours(12))));
            if (withinCancelWindow) actions.add(Action.CANCEL);
            if (v.getStage() == Stage.BLOCKED) actions.add(Action.RESUME);
            if (v.getRequiredAt() != null) {
                if (v.getStage() != Stage.IN_PROGRESS) actions.add(Action.ASSIGN);
            }
        }
        if (!actor.hasRole(RoleCode.STAFF) || !actor.hasPermission(SystemPermission.PROCESS_RETURNS)
            || !isAssigned(v, actor) || !scopes.canAccessFacility(actor, facility, FacilityScopeLevel.OPERATE)) return actions;
        if (terminal(v) && hasStoredItems(d)) {
            if (d.pickupAssignedStaffId != null && actor.userId().equals(d.pickupAssignedStaffId)
                && d.pickupAppointmentAt != null
                && !session(d.pickupOpenedAt, now) && d.pickupAttempts.size() < 2) {
                actions.add(Action.OPEN_PICKUP);
            }
            if (actor.userId().equals(d.pickupStaffId) && session(d.pickupOpenedAt, now)) {
                if (d.pickupAttempts.size() < 2 && d.pickupVerifiedAt == null) actions.add(Action.VERIFY_PICKUP);
                if (d.pickupVerifiedAt != null) actions.add(Action.RELEASE_ITEM);
            }
            return actions;
        }
        if (terminal(v)) return actions;
        if (v.getStage() == Stage.BLOCKED || d.disputeOpen || v.getRequiredAt() == null) return actions;
        if (v.getScheduledAt() != null
            && d.reply != Reply.CANNOT_ATTEND
            && d.attempts.size() < VERIFICATION_LIMIT
            && (d.attendance != Attendance.PRESENT || !session(d.arrivedAt, now))) {
            actions.add(Action.RECORD_PRESENT);
        }
        if (v.getStage() == Stage.AWAITING_ATTENDANCE
            && d.attendance != Attendance.PRESENT && d.attendance != Attendance.ABSENT
            && !now.isBefore(v.getScheduledAt())) actions.add(Action.RECORD_ABSENT);
        if (d.startedAt == null && d.attendance == Attendance.PRESENT && d.verifiedAt == null && session(d.arrivedAt, now)
            && d.attempts.size() < VERIFICATION_LIMIT
            && !normalize(v.getRental().getReservation().getIdentityDocumentNumber()).isEmpty()) actions.add(Action.VERIFY);
        if (v.getStage() == Stage.AWAITING_ATTENDANCE && d.notifiedAt != null && d.responseDueAt != null
            && (d.attendance == Attendance.ABSENT || d.attendance == Attendance.PRESENT)) {
            boolean verifiedInPerson = d.attendance == Attendance.PRESENT && within(d.arrivedAt, now, d.verifiedAt);
            boolean scheduledWindowOpen = !now.isBefore(v.getScheduledAt()) && !now.isBefore(d.responseDueAt);
            if (verifiedInPerson || scheduledWindowOpen) actions.add(Action.START);
        }
        if (v.getStage() == Stage.IN_PROGRESS && !d.disputeOpen) actions.addAll(List.of(Action.SAVE_ITEM, Action.COMPLETE));
        return actions;
    }

    private Entry view(ActorPrincipal actor, ViolationRecovery v, Details d, Instant now) {
        var rental = v.getRental();
        var task = v.getTask();
        var staff = task == null ? null : task.getAssignedTo();
        boolean customer = actor.hasRole(RoleCode.CUSTOMER);
        var owner = switch (v.getStage()) {
            case REVIEW_REQUIRED, AWAITING_ASSIGNMENT, AWAITING_APPROVAL, BLOCKED -> v.isEscalated() ? "BO" : "Quản lý";
            case AWAITING_ACCEPTANCE, AWAITING_ATTENDANCE, APPROVED, IN_PROGRESS -> v.isEscalated() ? "BO" : "Nhân viên";
            case COMPLETED, CANCELLED -> v.isFinancePending() || v.isPickupEscalated() ? "BO" : hasStoredItems(d) ? "Cơ sở" : null;
        };
        var communications = new ArrayList<CommunicationEntry>();
        if (d.communications != null && !d.communications.isEmpty()) {
            communications.addAll(d.communications);
        } else {
            if (d.customerNote != null && !d.customerNote.isBlank()) {
                communications.add(new CommunicationEntry("CUSTOMER", rental.getCustomer().getFullName(),
                    d.reply == Reply.DISPUTED ? Action.DISPUTE.name() : (d.reply != null ? d.reply.name() : "DISPUTE"),
                    d.notifiedAt != null ? d.notifiedAt : v.getRequiredAt(), d.customerNote,
                    d.customerAttachmentIds == null ? List.of() : List.copyOf(d.customerAttachmentIds)));
            }
            if (d.managerResponseNote != null && !d.managerResponseNote.isBlank()) {
                communications.add(new CommunicationEntry("MANAGER", "Quản lý cơ sở", "RESUME",
                    d.managerRespondedAt != null ? d.managerRespondedAt : now, d.managerResponseNote,
                    d.managerAttachmentIds == null ? List.of() : List.copyOf(d.managerAttachmentIds)));
            }
        }
        return new Entry(v.getId(), rental.getId(), rental.getReservation().getId(), rental.getReservation().getReservationCode(),
            rental.getCustomer().getFullName(), rental.getFacility().getId(),
            rental.getStorageUnit().getCode(), task == null ? null : task.getId(), staff == null ? null : staff.getFullName(),
            v.getReason(), v.getStage(), v.getSourceDeadlineAt(), v.getRequiredAt(), v.getScheduledAt(), v.getDeadlineAt(),
            v.getActionDueAt(), v.getOverdueAt(), v.getCompletedAt(), v.getPickupDueAt(), v.getPickupReviewAt(),
            v.isEscalated(), v.isPickupEscalated(), v.isFinancePending(), v.isFinanceClosed(), owner, d.attendance,
            d.reply, d.notifiedAt, d.responseDueAt, d.arrivedAt, d.verifiedAt, d.attempts.size(), d.pickupAttempts.size(),
            needsWitness(d, now), customer ? null : d.attendanceEvidenceId, customer ? null : d.beforeEvidenceId,
            customer ? null : d.afterEvidenceId, customer ? null : d.reasonNote, d.customerNote,
            customer ? null : d.report,
            d.items.values().stream().map(item -> customer ? new Item(item.code(), item.name(), item.condition(),
                null, null, item.disposition(), item.releasedAt(), null,
                item.liquidation() == null ? null : new Liquidation(item.liquidation().recordedAt(), null, null, null)) : item).toList(),
            customer ? List.of() : d.history,
            allowed(actor, v, d, now), customer ? v.getRequiredAt() : detectedAt(v, d), customer ? null : reviewDeadline(v, d),
            customer ? null : businessReviewDeadline(v, d),
            !customer && v.getStage() == Stage.REVIEW_REQUIRED ? reviewEvidence.read(v, now) : null,
            verification(v, d, false, now), verification(v, d, true, now), true,
            d.pickupAppointmentAt, d.managerResponseNote, d.managerRespondedAt,
            d.customerAttachmentIds == null ? List.of() : List.copyOf(d.customerAttachmentIds),
            d.managerAttachmentIds == null ? List.of() : List.copyOf(d.managerAttachmentIds),
            List.copyOf(communications));
    }

    private boolean needsWitness(Details d, Instant now) {
        return d.needsWitness || !within(d.arrivedAt, now, d.verifiedAt);
    }

    private Verification verification(ViolationRecovery v, Details d, boolean pickup, Instant now) {
        int used = (pickup ? d.pickupAttempts : d.attempts).size();
        int remaining = Math.max(0, VERIFICATION_LIMIT - used);
        boolean matched = (pickup ? d.pickupVerifiedAt : d.verifiedAt) != null;
        boolean canReceive = pickup ? matched && session(d.pickupOpenedAt, now) : within(d.arrivedAt, now, d.verifiedAt);
        String message;
        if (canReceive) message = "Xác minh thành công. Có thể bàn giao hàng cho khách trong phiên hiện tại.";
        else if (matched) message = "Phiên xác minh đã hết hiệu lực. Chưa được bàn giao hàng cho khách.";
        else if (normalize(v.getRental().getReservation().getIdentityDocumentNumber()).isEmpty())
            message = "Hồ sơ thiếu giấy tờ đối chiếu. Chưa được bàn giao hàng cho khách.";
        else if (remaining == 0)
            message = "Xác minh không thành công sau " + VERIFICATION_LIMIT + "/" + VERIFICATION_LIMIT + " lượt. Đã hết lượt xác minh; chưa được bàn giao hàng cho khách.";
        else if (used > 0)
            message = "Giấy tờ không khớp (lần " + used + "/" + VERIFICATION_LIMIT + "). Còn " + remaining + " lượt xác minh; chưa được bàn giao hàng cho khách.";
        else message = "Chưa xác minh danh tính. Được đối chiếu tối đa " + VERIFICATION_LIMIT + " lượt; chưa được bàn giao hàng cho khách.";
        if (!pickup && !canReceive) message += " Thu hồi vẫn tiếp tục theo lịch; hàng được lưu tạm với nhân chứng.";
        return new Verification(used, VERIFICATION_LIMIT, remaining, matched, canReceive, message);
    }

    private Instant reviewDeadline(ViolationRecovery v, Details d) {
        if (d.reviewDueAt != null) return d.reviewDueAt;
        if (v.getStage() != Stage.REVIEW_REQUIRED) return null;
        var detected = detectedAt(v, d);
        return detected == null ? v.getActionDueAt() : working(detected, 1);
    }

    private Instant detectedAt(ViolationRecovery v, Details d) {
        return d.history.stream().filter(event -> "DETECT".equals(event.action()))
            .map(HistoryEvent::time).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(v.getCreatedAt());
    }

    private Instant businessReviewDeadline(ViolationRecovery v, Details d) {
        return d.businessReviewDueAt != null ? d.businessReviewDueAt
            : v.getStage() == Stage.REVIEW_REQUIRED && v.isEscalated() ? v.getActionDueAt() : null;
    }

    private boolean canRead(ActorPrincipal actor, ViolationRecovery v) {
        if (actor.hasRole(RoleCode.CUSTOMER)) return v.getRequiredAt() != null && v.getRental().getCustomer().getId().equals(actor.userId());
        if (actor.hasRole(RoleCode.STAFF) && (!actor.hasPermission(SystemPermission.PROCESS_RETURNS) || !isAssigned(v, actor))) return false;
        return scopes.canAccessFacility(actor, v.getRental().getFacility().getId(), FacilityScopeLevel.READ);
    }

    private boolean isAssigned(ViolationRecovery v, ActorPrincipal actor) {
        return v.getTask() != null && v.getTask().getAssignedTo() != null
            && v.getTask().getAssignedTo().getId().equals(actor.userId());
    }

    private User staffAt(UUID id, ViolationRecovery v) {
        return eligibleStaff(v).stream()
            .filter(user -> user.getId().equals(id)).findFirst()
            .orElseThrow(() -> ApiExceptions.validation("Cần chọn nhân viên đang hoạt động, có quyền xử lý trả kho tại đúng cơ sở.", null));
    }

    private User availableStaffAt(UUID id, ViolationRecovery v, Instant start, Instant end) {
        var staff = staffAt(id, v);
        if (end != null && !end.isAfter(Instant.now())) { start = Instant.now(); end = start.plus(WINDOW); }
        if (!staffAvailability.available(id, v, start, end))
            throw ApiExceptions.conflict("Nhân viên đã có lịch xử lý đơn khác trong thời gian thu hồi. Vui lòng chọn nhân viên khác.");
        return staff;
    }

    private List<User> eligibleStaff(ViolationRecovery v) {
        return users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.STAFF, v.getRental().getFacility().getId(),
            List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)).stream()
            .filter(user -> user.getRoles().stream().flatMap(role -> role.getEffectivePermissions().stream())
                .anyMatch(permission -> SystemPermission.PROCESS_RETURNS.code().equals(permission.getCode())))
            .toList();
    }

    private void recordCommunication(ActorPrincipal actor, String actorName, Details d, Command c, Instant now) {
        boolean customerReply = c.action() == Action.DISPUTE || c.action() == Action.WILL_ATTEND || c.action() == Action.CANNOT_ATTEND;
        boolean opsResponse = c.action() == Action.RESUME || c.action() == Action.CANCEL
            || c.action() == Action.CLOSE_REMEDIATION || c.action() == Action.BLOCK;
        if (!customerReply && !opsResponse) return;
        String note = clean(c.note());
        List<UUID> files = c.attachmentIds() == null ? List.of()
            : c.attachmentIds().stream().filter(Objects::nonNull).distinct().toList();
        if ((c.action() == Action.WILL_ATTEND || c.action() == Action.CANNOT_ATTEND) && note.isEmpty() && files.isEmpty()) {
            return;
        }
        if (d.communications == null) d.communications = new ArrayList<>();
        String role = customerReply ? "CUSTOMER" : (actor.hasRole(RoleCode.BUSINESS) ? "BUSINESS" : "MANAGER");
        d.communications.add(new CommunicationEntry(role, actorName, c.action().name(), now, note, files));
    }

    private List<UUID> attachments(ViolationRecovery v, ActorPrincipal actor, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 10) throw ApiExceptions.validation("Tối đa 10 tệp đính kèm mỗi lần gửi.", null);
        var unique = new ArrayList<UUID>();
        for (UUID id : ids) {
            if (id == null) throw ApiExceptions.validation("Mã tệp đính kèm không hợp lệ.", null);
            if (unique.contains(id)) continue;
            var file = files.findById(id).orElseThrow(() -> ApiExceptions.validation("Không tìm thấy tệp đính kèm.", null));
            if (file.getStatus() != FileAssetStatus.ACTIVE
                || !"VIOLATION_RECOVERY".equals(file.getEntityType())
                || !v.getId().equals(file.getEntityId())
                || file.getUploadedBy() == null
                || !actor.userId().equals(file.getUploadedBy().getId())) {
                throw ApiExceptions.validation("Tệp đính kèm phải do chính bạn tải lên cho đúng hồ sơ thu hồi.", null);
            }
            unique.add(id);
        }
        return unique;
    }

    private UUID evidence(ViolationRecovery v, ActorPrincipal actor, UUID id) {
        if (id == null || v.getTask() == null) throw ApiExceptions.validation("Cần ảnh hiện trường.", null);
        var file = files.findById(id).orElseThrow(() -> ApiExceptions.validation("Không tìm thấy ảnh hiện trường.", null));
        if (file.getStatus() != FileAssetStatus.ACTIVE || !"MAINTENANCE_TASK".equals(file.getEntityType())
            || !v.getTask().getId().equals(file.getEntityId()) || file.getUploadedBy() == null
            || !actor.userId().equals(file.getUploadedBy().getId()) || file.getContentType() == null
            || !file.getContentType().startsWith("image/"))
            throw ApiExceptions.validation("Ảnh phải do người thực hiện tải lên cho đúng nhiệm vụ.", null);
        return id;
    }

    private void block(ViolationRecovery v, Details d, String note, Instant now) {
        d.reasonNote = note;
        advance(v, Stage.BLOCKED, d.disputeOpen ? later(now, v.getActionDueAt()) : working(now, 1));
        notifyOperations(v, "Thu hồi đang tạm dừng, cần xử lý ngoại lệ");
    }

    private static void advance(ViolationRecovery v, Stage stage, Instant due) {
        v.setStage(stage);
        v.setActionDueAt(due);
        v.setEscalated(false);
    }

    private Instant working(Instant at, int hours) { return calendar.current().afterWorkingHours(at, hours); }

    private static Instant future(Instant at, Instant now) {
        if (at == null || !at.isAfter(now)) throw ApiExceptions.validation("Cần chọn mốc trong tương lai.", null);
        return at;
    }

    private static Instant later(Instant first, Instant second) { return second != null && second.isAfter(first) ? second : first; }
    private static boolean session(Instant start, Instant now) { return start != null && now.isBefore(start.plus(WINDOW)); }
    private static boolean within(Instant start, Instant now, Instant verified) { return verified != null && session(start, now); }
    private static boolean terminal(ViolationRecovery v) { return v.getStage() == Stage.COMPLETED || v.getStage() == Stage.CANCELLED; }
    private static boolean hasStoredItems(Details d) {
        return d.items.values().stream().anyMatch(ViolationRecoveryWorkflow::isPendingItem);
    }
    private static boolean isPendingItem(Item item) {
        return "STORED".equals(item.disposition()) && item.releasedAt() == null && item.liquidation() == null;
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static String normalize(String value) { return clean(value).toUpperCase(Locale.ROOT); }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw ApiExceptions.validation("Vui lòng nhập " + field + ".", null);
        return value.strip();
    }

    private Details details(ViolationRecovery v) {
        if (v.getDetailsJson() == null) return new Details();
        try { return mapper.readValue(v.getDetailsJson(), Details.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read recovery details", e); }
    }

    private void save(ViolationRecovery v, Details d) {
        try { v.setDetailsJson(mapper.writeValueAsString(d)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot save recovery details", e); }
    }

    private void notifyCustomer(ViolationRecovery v, String title) {
        notifications.createNotification(v.getRental().getCustomer().getId(), NotificationType.RETURN, title,
            customerRecoveryMessage(v) + " Mở thông báo trên web để xem hồ sơ thu hồi.", v.getId());
    }

    private void notifyCustomerWithDetail(ViolationRecovery v, String title, String detail) {
        String noteText = detail == null || detail.isBlank() ? "" : " Phản hồi của quản lý: " + detail.strip() + ".";
        notifications.createNotification(v.getRental().getCustomer().getId(), NotificationType.RETURN, title,
            customerRecoveryMessage(v) + noteText + " Mở thông báo trên web để xem chi tiết và tệp đính kèm.", v.getId());
    }

    private void notifyCustomerSchedule(ViolationRecovery v, Details d, Instant now, String title) {
        if (d.notifiedAt == null) {
            d.notifiedAt = now;
            d.responseDueAt = now.plus(Duration.ofHours(6));
            v.setResponseCheckAt(d.responseDueAt);
        }
        notifications.createNotification(v.getRental().getCustomer().getId(), NotificationType.RETURN, title,
            customerRecoveryMessage(v) + " Vui lòng phản hồi trước " + customerTime(d.responseDueAt)
                + ". Mở thông báo trên web để xác nhận có mặt hoặc gửi phản đối.", v.getId());
    }

    private String customerRecoveryMessage(ViolationRecovery v) {
        String reason = switch (v.getReason()) {
            case SETUP_OVERDUE -> "Quá hạn hoàn tất chuẩn bị";
            case INVENTORY_NO_SHOW -> "Không đến kiểm kê theo lịch hẹn";
            case INVENTORY_IDENTITY_FAILED -> "Xác minh kiểm kê chưa thành công";
            case INVENTORY_TIMEOUT -> "Quá hạn hoàn tất kiểm kê";
            case INVENTORY_CONFIRMATION_OVERDUE -> "Quá hạn 48 giờ phản hồi kết quả kiểm kê";
            case REMOVAL_OVERDUE -> "Quá hạn dọn hàng sau kiểm kê sai lệch";
            case HANDOVER_FOLLOW_UP -> "Bàn giao sau đối soát cần xử lý";
            case CONFIRMED_PROHIBITED_GOODS -> "Vi phạm hàng cấm đã xác minh";
        };
        return recoveryIdentity(v) + " Lý do: " + reason + ". Lịch thu hồi: " + customerTime(v.getScheduledAt())
            + ". Hạn hoàn tất: " + customerTime(v.getDeadlineAt()) + ".";
    }

    private String customerTime(Instant value) {
        return value == null ? "chưa xác định" : java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(BusinessTime.ZONE).format(value);
    }

    private void notifyStaff(ViolationRecovery v, String title) {
        if (v.getTask() == null || v.getTask().getAssignedTo() == null) return;
        notifications.createNotification(v.getTask().getAssignedTo().getId(), NotificationType.RETURN, title,
            "Mở mục Thu hồi kho để tiếp tục.", v.getId());
    }

    private void notifyOperations(ViolationRecovery v, String title) {
        notifications.createFacilityOperationsNotificationsOnce(v.getRental().getFacility().getId(), NotificationType.RETURN,
            title, recoveryIdentity(v) + " Mở Nhân viên & Nhiệm vụ → Thu hồi kho để xử lý.", v.getId());
    }

    @Transactional
    public void notifyPendingNotifications(UUID id) {
        var source = recoveries.findById(id).orElse(null);
        if (source == null) return;
        rentals.findByIdForUpdate(source.getRental().getId()).orElseThrow();
        var v = recoveries.lock(id).orElseThrow();
        em.refresh(v, LockModeType.PESSIMISTIC_WRITE);
        if (terminal(v)) return;
        var d = details(v);
        upgradeLegacyFlow(v, d, Instant.now());
        if (terminal(v)) { save(v, d); return; }
        notifyCoordinationRequired(v);
        if (v.getRequiredAt() != null && d.notifiedAt == null) {
            notifyCustomerSchedule(v, d, Instant.now(), "Thông báo thu hồi kho");
        }
        save(v, d);
    }

    private void notifyCoordinationRequired(ViolationRecovery v) {
        String title = switch (v.getStage()) {
            case REVIEW_REQUIRED -> "Cần phân công nhân viên thu hồi";
            case AWAITING_ASSIGNMENT -> "Cần phân công nhân viên thu hồi";
            case AWAITING_APPROVAL -> "Hồ sơ thu hồi cần điều phối";
            case BLOCKED -> "Thu hồi tạm dừng, cần xử lý";
            default -> "Hồ sơ thu hồi cần điều phối";
        };
        String step = switch (v.getStage()) {
            case REVIEW_REQUIRED -> "Cần phân công nhân viên thu hồi.";
            case AWAITING_ASSIGNMENT -> "Cần phân công nhân viên thu hồi.";
            case AWAITING_ACCEPTANCE, AWAITING_APPROVAL -> "Đã phân công nhân viên, thực hiện thu hồi theo lịch đã thông báo.";
            case BLOCKED -> "Hồ sơ đang tạm dừng, cần quản lý cơ sở hoặc chủ doanh nghiệp xem xét xử lý.";
            default -> "Cần theo dõi tiến độ và xử lý các bước tiếp theo của hồ sơ thu hồi.";
        };
        for (var manager : users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.MANAGER,
            v.getRental().getFacility().getId(), List.of(FacilityScopeLevel.MANAGE))) {
            notifications.createNotificationOnce(manager.getId(), NotificationType.RETURN, title,
                recoveryIdentity(v) + " " + step + " Mở Nhân viên & Nhiệm vụ → Thu hồi kho để xử lý.", v.getId());
        }
        for (var business : businessRecipients(v))
            notifications.createNotificationOnce(business.getId(), NotificationType.RETURN, title,
                recoveryIdentity(v) + " " + step + " Mở thông báo để theo dõi và xử lý hồ sơ thu hồi.", v.getId());
    }

    private String recoveryIdentity(ViolationRecovery v) {
        return "Đơn " + v.getRental().getReservation().getReservationCode() + " · Gian "
            + v.getRental().getStorageUnit().getCode() + ".";
    }

    private void notifyBusiness(ViolationRecovery v, String title) {
        for (var user : businessRecipients(v))
            notifications.createNotificationOnce(user.getId(), NotificationType.RETURN, title,
                recoveryIdentity(v) + " Mở hồ sơ thu hồi để xem căn cứ và quyết định xử lý.", v.getId());
    }

    private List<User> businessRecipients(ViolationRecovery v) {
        return users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE).stream().filter(user -> {
            var granted = userFacilityScopes.findByUserId(user.getId());
            return granted.isEmpty() || granted.stream().anyMatch(scope ->
                scope.getFacility().getId().equals(v.getRental().getFacility().getId())
                    && scope.getScopeLevel().includes(FacilityScopeLevel.MANAGE));
        }).toList();
    }
}
