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

    public enum Action {
        CONFIRM_VIOLATION, ASSIGN, ACCEPT, DECLINE, NOTICE, WILL_ATTEND, CANNOT_ATTEND, DISPUTE,
        RESCHEDULE, RECORD_PRESENT, RECORD_ABSENT, VERIFY, APPROVE, START, SAVE_ITEM, BLOCK,
        RESUME, CANCEL, COMPLETE, OPEN_PICKUP, VERIFY_PICKUP, RELEASE_ITEM, FOLLOW_UP_PICKUP,
        REQUEST_FINANCE, RESOLVE_FINANCE, ASSIGN_PICKUP, CLOSE_REMEDIATION
    }

    public record Command(@jakarta.validation.constraints.NotNull Action action, UUID staffId, UUID witnessId, UUID evidenceId, UUID attemptId,
        Instant appointmentAt, @Size(max = 4000) String note, @Size(max = 32) String document,
        @Size(max = 80) String itemCode, @Size(max = 240) String itemName, @Size(max = 240) String condition,
        @Size(max = 240) String location, @Size(max = 40) String disposition) {}

    public record Item(String code, String name, String condition, String location, UUID evidenceId, String disposition,
        Instant releasedAt, UUID releaseEvidenceId) {}

    public record StaffOption(UUID id, String name) {}

    @Transactional(readOnly = true)
    public List<StaffOption> staff(ActorPrincipal actor, UUID id) {
        var v = recoveries.findById(id).filter(entry -> canRead(actor, entry) && !actor.hasRole(RoleCode.CUSTOMER))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        return eligibleStaff(v).stream().map(user -> new StaffOption(user.getId(), user.getFullName())).toList();
    }

    public record Entry(UUID id, UUID rentalId, UUID reservationId, UUID facilityId, String unitCode, UUID taskId,
        String staffName, Reason reason, Stage stage, Instant requiredAt, Instant scheduledAt, Instant deadlineAt,
        Instant actionDueAt, Instant overdueAt, Instant completedAt, Instant pickupDueAt, Instant pickupReviewAt,
        boolean escalated, boolean pickupEscalated, boolean financePending, boolean financeClosed,
        String owner, Attendance attendance, Reply reply, Instant notifiedAt, Instant responseDueAt,
        Instant arrivedAt, Instant verifiedAt, int verificationAttempts, int pickupAttempts, boolean needsWitness,
        UUID beforeEvidenceId, UUID afterEvidenceId, String reasonNote, String customerNote,
        String report, List<Item> items, List<Action> actions) {}

    public static final class Details {
        public Instant notifiedAt, responseDueAt, arrivedAt, verifiedAt, acceptedAt, startedAt, pickupOpenedAt, pickupVerifiedAt;
        public Reply reply;
        public boolean disputeOpen, needsWitness;
        public Attendance attendance;
        public UUID beforeEvidenceId, afterEvidenceId, attendanceEvidenceId, approvedBy, pickupStaffId;
        public Instant approvedAt;
        public String reasonNote, customerNote, report, plannedHandover;
        public List<UUID> attempts = new ArrayList<>();
        public List<UUID> pickupAttempts = new ArrayList<>();
        public Map<String, Item> items = new LinkedHashMap<>();
    }

    /** Called with the rental locked by the originating scheduler. */
    public boolean detect(Rental rental, ReturnCase source, Reason reason, Instant deadline, Instant now, boolean review) {
        if (deadline == null || now.isBefore(deadline) || rental.getActualReturnedAt() != null
            || rental.getCompletedAt() != null || rental.getInventoryEscalatedAt() != null) return false;
        if (recoveries.findByRental_IdOrderByCreatedAtDesc(rental.getId()).stream().anyMatch(v ->
            !terminal(v) || v.getReason() == reason && v.getSourceDeadlineAt().equals(deadline))) return false;
        if (tasks.findByRental_IdAndTaskTypeAndStatusIn(rental.getId(), MaintenanceTaskType.maintenance,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)).stream()
            .anyMatch(task -> task.getRecoveryType() != MaintenanceRecoveryType.NONE)) return false;
        var v = new ViolationRecovery();
        v.setRental(rental);
        v.setSourceReturnId(source == null ? null : source.getId());
        v.setReason(reason);
        v.setSourceDeadlineAt(deadline);
        advance(v, Stage.REVIEW_REQUIRED, working(now, 1));
        if (!review) activate(v, now);
        recoveries.saveAndFlush(v);
        notifyOperations(v, review ? "Cần rà soát căn cứ thu hồi" : "Yêu cầu thu hồi chờ phân công");
        if (!review) notifyCustomer(v, "Đã mở yêu cầu thu hồi");
        return true;
    }

    public boolean blocksLegacyReturn(UUID rentalId) {
        return recoveries.findByRental_IdOrderByCreatedAtDesc(rentalId).stream()
            .anyMatch(v -> v.getRequiredAt() != null && v.getStage() != Stage.CANCELLED);
    }

    @Transactional(readOnly = true)
    public List<Entry> list(ActorPrincipal actor, int page) {
        var paging = PageRequest.of(Math.max(0, page), 30);
        List<ViolationRecovery> rows;
        if (actor.hasRole(RoleCode.CUSTOMER)) rows = recoveries.findByRental_Customer_IdOrderByCreatedAtDesc(actor.userId(), paging);
        else if (actor.hasRole(RoleCode.STAFF)) rows = recoveries.findByTask_AssignedTo_IdOrderByCreatedAtDesc(actor.userId(), paging);
        else if (actor.hasAnyRole(RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN)) {
            rows = scopes.hasGlobalScope(actor) ? recoveries.findAllByOrderByCreatedAtDesc(paging)
                : actor.facilityScopes().isEmpty() ? List.of()
                : recoveries.findByRental_Facility_IdInOrderByCreatedAtDesc(actor.facilityScopes().keySet(), paging);
        } else throw ApiExceptions.forbidden("Vai trò không có quyền xem thu hồi.");
        return rows.stream().filter(v -> canRead(actor, v)).map(v -> view(actor, v, details(v), Instant.now())).toList();
    }

    @Transactional
    public Entry execute(ActorPrincipal actor, UUID id, Command command) {
        var source = recoveries.findById(id).filter(v -> canRead(actor, v))
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ thu hồi."));
        rentals.findByIdForUpdate(source.getRental().getId()).orElseThrow();
        var v = recoveries.lock(id).orElseThrow();
        em.refresh(v, LockModeType.PESSIMISTIC_WRITE);
        if (!canRead(actor, v)) throw ApiExceptions.forbidden("Bạn không còn được phân công hồ sơ này.");
        var d = details(v);
        var now = Instant.now();
        if (command.action() == Action.COMPLETE && v.getStage() == Stage.COMPLETED
            && actor.userId().equals(v.getCompletedBy())) return view(actor, v, d, now);
        if (!allowed(actor, v, d, now).contains(command.action()))
            throw ApiExceptions.conflict("Thao tác chưa đủ điều kiện hoặc hồ sơ đã chuyển bước.");
        var before = view(actor, v, d, now);
        apply(actor, v, d, command, now);
        save(v, d);
        var after = view(actor, v, d, now);
        audit.recordMutation("RECOVERY_" + command.action(), "violation_recovery", id, v.getRental().getFacility().getId(),
            before, Map.of("entry", after, "note", clean(command.note())));
        return after;
    }

    private void apply(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        switch (c.action()) {
            case CONFIRM_VIOLATION -> {
                d.reasonNote = required(c.note(), "căn cứ xác định vi phạm");
                validateActivation(v, now);
                activate(v, now);
                notifyCustomer(v, "Đã mở yêu cầu thu hồi");
            }
            case ASSIGN -> assign(actor, v, d, c, now);
            case ACCEPT -> {
                d.acceptedAt = now;
                advance(v, d.startedAt == null ? Stage.AWAITING_ATTENDANCE : Stage.BLOCKED, later(now, v.getScheduledAt()).plus(WINDOW));
            }
            case DECLINE -> {
                d.reasonNote = required(c.note(), "lý do từ chối");
                v.getTask().setAssignedTo(null);
                advance(v, Stage.AWAITING_ASSIGNMENT, now.plusSeconds(3600));
            }
            case NOTICE -> {
                if (!Set.of("APP", "EMAIL", "DELIVERED_IN_PERSON", "OTHER").contains(clean(c.disposition())))
                    throw ApiExceptions.validation("Cần chọn kênh thông báo.", null);
                d.reasonNote = c.disposition() + " · " + required(c.note(), "mã hoặc chứng cứ thông báo hợp lệ");
                if (d.notifiedAt == null) {
                    d.notifiedAt = now;
                    d.responseDueAt = now.plus(Duration.ofHours(6));
                    v.setResponseCheckAt(d.responseDueAt);
                }
            }
            case WILL_ATTEND, CANNOT_ATTEND -> {
                d.reply = Reply.valueOf(c.action().name());
                d.customerNote = clean(c.note());
            }
            case DISPUTE -> {
                d.customerNote = required(c.note(), "nội dung phản đối");
                d.reply = Reply.DISPUTED;
                if (terminal(v)) {
                    v.setFinancePending(true);
                    notifyBusiness(v, "Khách gửi khiếu nại sau thu hồi");
                } else if (!d.disputeOpen) {
                    d.disputeOpen = true;
                    advance(v, Stage.BLOCKED, now.plus(Duration.ofHours(6)));
                    notifyOperations(v, "Khách phản đối. Cần tạm dừng và rà soát");
                }
            }
            case RESCHEDULE -> reschedule(v, d, c, now);
            case RECORD_PRESENT, RECORD_ABSENT -> attendance(actor, v, d, c, now);
            case VERIFY, VERIFY_PICKUP -> verify(v, d, c, now);
            case APPROVE -> approve(actor, v, d, c, now);
            case START -> start(actor, v, d, c, now);
            case SAVE_ITEM -> saveItem(actor, v, d, c, now);
            case BLOCK -> block(v, d, required(c.note(), "lý do tạm dừng"), now);
            case RESUME -> resume(v, d, c, now);
            case CANCEL -> cancel(v, d, c, now);
            case CLOSE_REMEDIATION -> closeRemediation(v, d, c, now);
            case COMPLETE -> complete(actor, v, d, c, now);
            case ASSIGN_PICKUP -> {
                v.getTask().setAssignedTo(staffAt(c.staffId(), v));
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
        v.setRequiredAt(now);
        v.setScheduledAt(nextOpening(now.plus(Duration.ofHours(24))));
        v.setDeadlineAt(now.plus(Duration.ofHours(72)));
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
            case INVENTORY_NO_SHOW, INVENTORY_IDENTITY_FAILED, INVENTORY_TIMEOUT ->
                r.isInventoryPending() && r.getInventoryResultPublishedAt() == null ? r.getInventoryFinalDeadlineAt() : null;
            case REMOVAL_OVERDUE, HANDOVER_FOLLOW_UP -> r.getInventoryRemovalDeadlineAt();
        };
        if (current == null || now.isBefore(current))
            throw ApiExceptions.conflict("Mốc nguồn đã thay đổi hoặc chưa đến hạn.");
        if (v.getSourceReturnId() != null) {
            var rc = returns.findByIdForUpdate(v.getSourceReturnId()).orElseThrow();
            if (rc.getInspectedAt() != null || rc.getReadyForInspectionAt() != null || ReturnAttendancePolicy.protectsFromRecovery(rc))
                throw ApiExceptions.conflict("Hồ sơ đã bàn giao hoặc đang được bảo vệ bởi phiên tiếp nhận.");
        }
    }

    private void assign(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        var staff = staffAt(c.staffId(), v);
        var task = v.getTask();
        if (task == null) {
            var r = v.getRental();
            task = new MaintenanceTask();
            task.setRental(r);
            task.setFacility(r.getFacility());
            task.setStorageUnit(r.getStorageUnit());
            task.setReportedBy(users.findById(actor.userId()).orElseThrow());
            task.setTitle("Thu hồi kho do vi phạm");
            task.setReason("Thực hiện qua hồ sơ thu hồi đã được phê duyệt.");
            task.setPriority(TaskPriority.high);
            task.setRecoveryType(MaintenanceRecoveryType.VIOLATION);
            task.setDeadlineAt(v.getDeadlineAt());
            task.setDueAt(v.getDeadlineAt().atZone(BusinessTime.ZONE).toLocalDate());
            v.setTask(tasks.saveAndFlush(task));
        }
        task.setAssignedTo(staff);
        d.acceptedAt = null;
        advance(v, Stage.AWAITING_ACCEPTANCE, now.plus(Duration.ofHours(1)));
        notifyStaff(v, "Bạn được phân công nhiệm vụ thu hồi");
        notifyCustomer(v, "Đã phân công nhân viên thu hồi");
    }

    private void reschedule(ViolationRecovery v, Details d, Command c, Instant now) {
        Instant appointment = future(c.appointmentAt(), now);
        if (!appointment.equals(nextOpening(appointment)))
            throw ApiExceptions.validation("Lịch hẹn phải nằm trong ca làm việc.", null);
        d.reasonNote = required(c.note(), "lý do đổi lịch");
        if (!appointment.equals(v.getScheduledAt())) {
            d.notifiedAt = null;
            d.responseDueAt = null;
            d.reply = null;
            v.setResponseCheckAt(null);
        }
        v.setScheduledAt(appointment);
        d.attendance = null;
        d.arrivedAt = null;
        d.verifiedAt = null;
        d.attempts.clear();
        advance(v, v.getTask() == null || v.getTask().getAssignedTo() == null ? Stage.AWAITING_ASSIGNMENT
            : d.acceptedAt == null ? Stage.AWAITING_ACCEPTANCE : Stage.AWAITING_ATTENDANCE,
            d.acceptedAt == null ? now.plus(Duration.ofHours(1)) : appointment.plus(WINDOW));
        notifyCustomer(v, "Lịch thu hồi đã thay đổi");
        notifyStaff(v, "Lịch thu hồi đã thay đổi");
    }

    private void attendance(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        boolean present = c.action() == Action.RECORD_PRESENT;
        Instant actual = c.appointmentAt() == null ? now : c.appointmentAt();
        if (actual.isAfter(now) || actual.isBefore(v.getRequiredAt()))
            throw ApiExceptions.validation("Giờ ghi nhận khách không hợp lệ.", null);
        d.attendanceEvidenceId = evidence(v, actor, c.evidenceId());
        d.attendance = present ? Attendance.PRESENT : Attendance.ABSENT;
        d.arrivedAt = present ? actual : null;
        d.verifiedAt = null;
        d.attempts.clear();
        d.reasonNote = clean(c.note());
        if (d.startedAt != null || d.disputeOpen) {
            block(v, d, "Khách có mặt khi hồ sơ đang tạm dừng hoặc đã bắt đầu thu hồi.", now);
        } else {
            advance(v, present ? Stage.AWAITING_ATTENDANCE : Stage.AWAITING_APPROVAL,
                present ? actual.plus(WINDOW) : working(now, 1));
        }
    }

    private void verify(ViolationRecovery v, Details d, Command c, Instant now) {
        boolean pickup = c.action() == Action.VERIFY_PICKUP;
        var attempts = pickup ? d.pickupAttempts : d.attempts;
        if (c.attemptId() == null) throw ApiExceptions.validation("Thiếu mã lần đối chiếu.", null);
        if (attempts.contains(c.attemptId())) return;
        String expected = normalize(v.getRental().getReservation().getIdentityDocumentNumber());
        String actual = normalize(c.document());
        if (expected.isEmpty()) throw ApiExceptions.conflict("Hồ sơ thiếu giấy tờ đối chiếu. Cần Manager xử lý.");
        if (!com.storagehub.validation.IdentityDocumentPolicy.isValid(actual)
            && !actual.matches("(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6,20}"))
            throw ApiExceptions.validation("Giấy tờ không đúng định dạng.", null);
        attempts.add(c.attemptId());
        if (expected.equals(actual)) {
            if (pickup) d.pickupVerifiedAt = now;
            else {
                d.verifiedAt = now;
                if (v.getStage() != Stage.BLOCKED) advance(v, Stage.AWAITING_APPROVAL, working(now, 1));
            }
        } else if (attempts.size() == 2 && !pickup) {
            block(v, d, "Đã hết số lượt xác minh danh tính.", now);
        } else if (attempts.size() == 2) {
            v.setPickupReviewAt(now);
            notifyOperations(v, "Cần đối soát giấy tờ nhận tài sản");
        }
    }

    private void approve(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        d.reasonNote = required(c.note(), "căn cứ cho phép thu hồi");
        d.needsWitness |= d.attendance == Attendance.ABSENT || d.reply == Reply.DISPUTED || c.witnessId() != null;
        if (c.witnessId() != null) {
            var witness = staffAt(c.witnessId(), v);
            if (witness.getId().equals(v.getTask().getAssignedTo().getId()))
                throw ApiExceptions.validation("Nhân chứng phải khác nhân viên phụ trách.", null);
            v.getTask().setRecoveryWitness(witness);
        }
        d.approvedAt = now;
        d.approvedBy = actor.userId();
        d.disputeOpen = false;
        advance(v, Stage.APPROVED, working(later(now, v.getScheduledAt()), 1));
        notifyStaff(v, "Đã phê duyệt thu hồi");
    }

    private void start(ActorPrincipal actor, ViolationRecovery v, Details d, Command c, Instant now) {
        if (d.needsWitness) {
            UUID witnessId = c.witnessId() != null ? c.witnessId()
                : v.getTask().getRecoveryWitness() == null ? null : v.getTask().getRecoveryWitness().getId();
            var witness = staffAt(witnessId, v);
            if (actor.userId().equals(witness.getId())) throw ApiExceptions.validation("Cần nhân chứng độc lập.", null);
            v.getTask().setRecoveryWitness(witness);
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
        String code = normalize(required(c.itemCode(), "mã kiện"));
        if (d.items.containsKey(code)) throw ApiExceptions.conflict("Mã kiện đã được ghi nhận.");
        String disposition = c.disposition();
        if (!"STORED".equals(disposition) && !"HANDED_OVER".equals(disposition))
            throw ApiExceptions.validation("Cần chọn giao khách hoặc lưu tạm.", null);
        boolean handover = "HANDED_OVER".equals(disposition);
        if (handover && !within(d.arrivedAt, now, d.verifiedAt))
            throw ApiExceptions.conflict("Cần xác minh quyền nhận tài sản còn hiệu lực.");
        d.items.put(code, new Item(code, required(c.itemName(), "tên kiện"), required(c.condition(), "tình trạng"),
            required(c.location(), "nơi lưu tạm hoặc người nhận"), evidence(v, actor, c.evidenceId()),
            disposition, handover ? now : null, null));
    }

    private void resume(ViolationRecovery v, Details d, Command c, Instant now) {
        d.reasonNote = required(c.note(), "kết luận xử lý ngoại lệ");
        if (d.disputeOpen) d.needsWitness = true;
        d.disputeOpen = false;
        advance(v, v.getRequiredAt() == null ? Stage.REVIEW_REQUIRED
            : v.getTask() == null || v.getTask().getAssignedTo() == null ? Stage.AWAITING_ASSIGNMENT
            : d.acceptedAt == null ? Stage.AWAITING_ACCEPTANCE : Stage.AWAITING_ATTENDANCE,
            c.appointmentAt() == null ? working(now, 1) : future(c.appointmentAt(), now));
        if (d.attendance == Attendance.PRESENT && !within(d.arrivedAt, now, d.verifiedAt)) {
            d.attendance = null;
            d.arrivedAt = null;
            d.verifiedAt = null;
            d.attempts.clear();
        }
    }

    private void cancel(ViolationRecovery v, Details d, Command c, Instant now) {
        d.reasonNote = required(c.note(), "căn cứ hủy thu hồi và phương án khắc phục");
        if (v.getRequiredAt() != null) {
            var rental = v.getRental();
            var unit = rental.getStorageUnit();
            em.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
            if (d.startedAt != null || unit.getStatus() != StorageUnitStatus.occupied
                || rentals.existsOtherUnreturnedRental(unit.getId(), rental.getId())
                || assignments.existsOtherActiveAssignment(unit.getId(), rental.getReservation().getId())) {
                block(v, d, "Cần BO xử lý theo hiện trạng. " + d.reasonNote, now);
                v.setEscalated(true);
                notifyBusiness(v, "Cần khắc phục sau khi đã bắt đầu thu hồi");
                return;
            }
            restoreRental(v);
        }
        if (v.getTask() != null) v.getTask().setStatus(MaintenanceTaskStatus.cancelled);
        advance(v, Stage.CANCELLED, null);
        v.setResponseCheckAt(null);
        notifyCustomer(v, "Yêu cầu thu hồi đã được hủy");
    }

    private void closeRemediation(ViolationRecovery v, Details d, Command c, Instant now) {
        d.reasonNote = required(c.note(), "kết luận khắc phục, tình trạng gian và tài sản");
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
        notifyCustomer(v, "BO đã xử lý yêu cầu thu hồi bị hủy. Vui lòng liên hệ cơ sở.");
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
            throw ApiExceptions.conflict("Gian đã có hồ sơ hoặc phân kho khác. Cần BO khắc phục.");
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
        String code = normalize(required(c.itemCode(), "mã kiện"));
        var item = d.items.get(code);
        if (item == null || !"STORED".equals(item.disposition()) || item.releasedAt() != null)
            throw ApiExceptions.conflict("Kiện hàng không còn chờ nhận.");
        d.items.put(code, new Item(code, item.name(), item.condition(), item.location(), item.evidenceId(),
            item.disposition(), now, evidence(v, actor, c.evidenceId())));
        if (!hasStoredItems(d)) v.setPickupReviewAt(null);
    }

    @Transactional
    public void processDue(UUID id, Instant now) {
        var v = recoveries.lock(id).orElse(null);
        if (v == null) return;
        var d = details(v);
        if (v.getDeadlineAt() != null && !now.isBefore(v.getDeadlineAt()) && !terminal(v) && v.getOverdueAt() == null) {
            v.setOverdueAt(v.getDeadlineAt());
            notifyOperations(v, "Nhiệm vụ thu hồi đã quá hạn 72 giờ");
            notifyBusiness(v, "Nhiệm vụ thu hồi đã quá hạn 72 giờ");
        }
        if (!terminal(v) && d.responseDueAt != null && !now.isBefore(d.responseDueAt)) {
            if (d.reply == null) {
                d.reply = Reply.NO_RESPONSE;
                notifyOperations(v, "Khách chưa phản hồi thông báo thu hồi");
            }
            v.setResponseCheckAt(null);
        }
        if (v.getActionDueAt() != null && !now.isBefore(v.getActionDueAt()) && !terminal(v)) {
            if (v.getStage() == Stage.AWAITING_ACCEPTANCE && d.startedAt == null && !d.disputeOpen) {
                v.getTask().setAssignedTo(null);
                advance(v, Stage.AWAITING_ASSIGNMENT, now.plus(Duration.ofHours(1)));
                notifyOperations(v, "Nhân viên chưa tiếp nhận nhiệm vụ thu hồi");
            } else if (v.getStage() == Stage.AWAITING_ATTENDANCE || v.getStage() == Stage.APPROVED) {
                if (d.attendance == null) d.attendance = Attendance.UNDETERMINED;
                block(v, d, "Đã đến hạn. Cần Manager rà soát tiến độ hoặc kết quả tiếp nhận.", now);
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
    }

    private List<Action> allowed(ActorPrincipal actor, ViolationRecovery v, Details d, Instant now) {
        List<Action> actions = new ArrayList<>();
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            if (v.getRequiredAt() == null) return actions;
            if (!terminal(v) && !d.disputeOpen) actions.addAll(List.of(Action.WILL_ATTEND, Action.CANNOT_ATTEND));
            actions.add(Action.DISPUTE);
            if (terminal(v) && !v.isFinancePending()) actions.add(Action.REQUEST_FINANCE);
            return actions;
        }
        var facility = v.getRental().getFacility().getId();
        boolean manager = actor.hasAnyRole(RoleCode.MANAGER, RoleCode.ADMIN)
            && actor.hasPermission(SystemPermission.MANAGE_STAFF_TASKS)
            && scopes.canAccessFacility(actor, facility, FacilityScopeLevel.MANAGE);
        boolean business = actor.hasAnyRole(RoleCode.BUSINESS, RoleCode.ADMIN)
            && scopes.canAccessFacility(actor, facility, FacilityScopeLevel.MANAGE);
        if (business && v.isFinancePending()) actions.add(Action.RESOLVE_FINANCE);
        if ((manager || business) && terminal(v) && hasStoredItems(d)) {
            actions.add(Action.FOLLOW_UP_PICKUP);
            actions.add(Action.ASSIGN_PICKUP);
        }
        if (!terminal(v) && (manager || business && v.isEscalated())) {
            actions.add(Action.CANCEL);
            if (v.getStage() == Stage.REVIEW_REQUIRED) actions.add(Action.CONFIRM_VIOLATION);
            if (v.getStage() == Stage.BLOCKED || v.isEscalated()) actions.add(Action.RESUME);
            if (business && v.getStage() == Stage.BLOCKED && d.startedAt != null) actions.add(Action.CLOSE_REMEDIATION);
            if (v.getRequiredAt() != null) {
                if (d.startedAt == null && !d.disputeOpen) actions.add(Action.RESCHEDULE);
                if (!d.disputeOpen && v.getStage() != Stage.IN_PROGRESS) actions.add(Action.ASSIGN);
                actions.add(Action.NOTICE);
                if (d.acceptedAt != null && d.notifiedAt != null
                    && (d.attendance == Attendance.PRESENT && within(d.arrivedAt, now, d.verifiedAt)
                        || d.attendance == Attendance.ABSENT && !now.isBefore(d.responseDueAt))
                    && List.of(Stage.AWAITING_APPROVAL, Stage.BLOCKED, Stage.AWAITING_ATTENDANCE).contains(v.getStage()))
                    actions.add(Action.APPROVE);
            }
        }
        if (!actor.hasRole(RoleCode.STAFF) || !actor.hasPermission(SystemPermission.PROCESS_RETURNS)
            || !isAssigned(v, actor) || !scopes.canAccessFacility(actor, facility, FacilityScopeLevel.OPERATE)) return actions;
        if (terminal(v) && hasStoredItems(d)) {
            if (!session(d.pickupOpenedAt, now) && d.pickupAttempts.size() < 2) actions.add(Action.OPEN_PICKUP);
            if (actor.userId().equals(d.pickupStaffId) && session(d.pickupOpenedAt, now)) {
                if (d.pickupAttempts.size() < 2 && d.pickupVerifiedAt == null) actions.add(Action.VERIFY_PICKUP);
                if (d.pickupVerifiedAt != null) actions.add(Action.RELEASE_ITEM);
            }
            return actions;
        }
        if (terminal(v)) return actions;
        if (d.acceptedAt == null) {
            if (v.getStage() == Stage.AWAITING_ACCEPTANCE) {
                actions.add(Action.ACCEPT);
                if (d.startedAt == null) actions.add(Action.DECLINE);
            }
            return actions;
        }
        actions.add(Action.BLOCK);
        if (d.attendance != Attendance.PRESENT) actions.add(Action.RECORD_PRESENT);
        if (List.of(Stage.AWAITING_ATTENDANCE, Stage.BLOCKED).contains(v.getStage()) && !d.disputeOpen
            && d.attendance != Attendance.PRESENT && d.attendance != Attendance.ABSENT
            && !now.isBefore(v.getScheduledAt().plus(WINDOW))) actions.add(Action.RECORD_ABSENT);
        if (d.attendance == Attendance.PRESENT && d.verifiedAt == null && session(d.arrivedAt, now)
            && d.attempts.size() < 2) actions.add(Action.VERIFY);
        if (v.getStage() == Stage.APPROVED && !d.disputeOpen && !now.isBefore(v.getScheduledAt())
            && (d.attendance == Attendance.ABSENT || within(d.arrivedAt, now, d.verifiedAt))) actions.add(Action.START);
        if (v.getStage() == Stage.IN_PROGRESS && !d.disputeOpen) actions.addAll(List.of(Action.SAVE_ITEM, Action.COMPLETE));
        if (v.getStage() == Stage.BLOCKED && d.startedAt != null) actions.add(Action.SAVE_ITEM);
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
        return new Entry(v.getId(), rental.getId(), rental.getReservation().getId(), rental.getFacility().getId(),
            rental.getStorageUnit().getCode(), task == null ? null : task.getId(), staff == null ? null : staff.getFullName(),
            v.getReason(), v.getStage(), v.getRequiredAt(), v.getScheduledAt(), v.getDeadlineAt(), v.getActionDueAt(),
            v.getOverdueAt(), v.getCompletedAt(), v.getPickupDueAt(), v.getPickupReviewAt(), v.isEscalated(), v.isPickupEscalated(),
            v.isFinancePending(), v.isFinanceClosed(), owner, d.attendance, d.reply, d.notifiedAt, d.responseDueAt,
            d.arrivedAt, d.verifiedAt, d.attempts.size(), d.pickupAttempts.size(), d.needsWitness,
            customer ? null : d.beforeEvidenceId, customer ? null : d.afterEvidenceId, customer ? null : d.reasonNote,
            d.customerNote, customer ? null : d.report,
            d.items.values().stream().map(item -> customer ? new Item(item.code(), item.name(), item.condition(),
                null, null, item.disposition(), item.releasedAt(), null) : item).toList(), allowed(actor, v, d, now));
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
            .orElseThrow(() -> ApiExceptions.validation("Cần chọn nhân viên đang hoạt động tại đúng cơ sở.", null));
    }

    private List<User> eligibleStaff(ViolationRecovery v) {
        return users.findEligibleStaff(UserStatus.ACTIVE, RoleCode.STAFF, v.getRental().getFacility().getId(),
            List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE));
    }

    private UUID evidence(ViolationRecovery v, ActorPrincipal actor, UUID id) {
        if (id == null || v.getTask() == null) throw ApiExceptions.validation("Cần ảnh hiện trường.", null);
        var file = files.findById(id).orElseThrow(() -> ApiExceptions.validation("Không tìm thấy ảnh hiện trường.", null));
        if (file.getStatus() != FileAssetStatus.ACTIVE || !"MAINTENANCE_TASK".equals(file.getEntityType())
            || !v.getTask().getId().equals(file.getEntityId()) || file.getUploadedBy() == null
            || !actor.userId().equals(file.getUploadedBy().getId()) || file.getContentType() == null
            || !file.getContentType().startsWith("image/"))
            throw ApiExceptions.validation("Ảnh phải do nhân viên phụ trách tải lên cho đúng nhiệm vụ.", null);
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

    private Instant nextOpening(Instant candidate) {
        var s = calendar.current();
        var day = candidate.atZone(BusinessTime.ZONE).toLocalDate();
        while (true) {
            if (s.days().contains(day.getDayOfWeek()) && !s.holidays().contains(day)) {
                Instant open = day.atTime(s.opens()).atZone(BusinessTime.ZONE).toInstant();
                Instant close = (s.midnightClose() ? day.plusDays(1).atStartOfDay() : day.atTime(s.closes()))
                    .atZone(BusinessTime.ZONE).toInstant();
                if (candidate.isBefore(close)) return later(candidate, open);
            }
            day = day.plusDays(1);
        }
    }

    private static Instant future(Instant at, Instant now) {
        if (at == null || !at.isAfter(now)) throw ApiExceptions.validation("Cần chọn mốc trong tương lai.", null);
        return at;
    }

    private static Instant later(Instant first, Instant second) { return second != null && second.isAfter(first) ? second : first; }
    private static boolean session(Instant start, Instant now) { return start != null && now.isBefore(start.plus(WINDOW)); }
    private static boolean within(Instant start, Instant now, Instant verified) { return verified != null && session(start, now); }
    private static boolean terminal(ViolationRecovery v) { return v.getStage() == Stage.COMPLETED || v.getStage() == Stage.CANCELLED; }
    private static boolean hasStoredItems(Details d) {
        return d.items.values().stream().anyMatch(item -> "STORED".equals(item.disposition()) && item.releasedAt() == null);
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
            "Vui lòng mở mục Thu hồi kho để xem chi tiết.", v.getRental().getReservation().getId());
    }

    private void notifyStaff(ViolationRecovery v, String title) {
        if (v.getTask() == null || v.getTask().getAssignedTo() == null) return;
        notifications.createNotification(v.getTask().getAssignedTo().getId(), NotificationType.RETURN, title,
            "Mở mục Thu hồi kho để tiếp tục.", v.getId());
    }

    private void notifyOperations(ViolationRecovery v, String title) {
        notifications.createFacilityOperationsNotificationsOnce(v.getRental().getFacility().getId(), NotificationType.RETURN,
            title, "Mở mục Thu hồi kho để xử lý.", v.getId());
    }

    private void notifyBusiness(ViolationRecovery v, String title) {
        for (var user : users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE))
            notifications.createNotificationOnce(user.getId(), NotificationType.RETURN, title, "Mở mục Thu hồi kho để xử lý.", v.getId());
    }
}
