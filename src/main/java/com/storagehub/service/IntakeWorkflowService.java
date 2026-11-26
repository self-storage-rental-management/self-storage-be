package com.storagehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeWorkflowService {

    private static final DateTimeFormatter VN_TIME = DateTimeFormatter
        .ofPattern("HH:mm dd/MM/yyyy")
        .withZone(BusinessTime.ZONE);

    private final RentalRepository rentals;
    private final CheckInRepository checkIns;
    private final FileAssetRepository files;
    private final FacilityScopeService scope;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final ReturnCaseRepository returns;
    private final Clock clock;
    private final IntakePolicyProperties policy;
    private final IntakeTimelineCalculator timeline;
    private final IntakeVerificationAttemptRepository verificationAttempts;
    private final IntakeIdempotencyRecordRepository idempotencyRecords;
    private final MaintenanceTaskRepository maintenanceTasks;

    /** Legacy-compatible constructor for unit tests without new Spring beans. */
    public IntakeWorkflowService(
        RentalRepository rentals,
        CheckInRepository checkIns,
        FileAssetRepository files,
        FacilityScopeService scope,
        NotificationService notifications,
        ObjectMapper json,
        ReturnCaseRepository returns
    ) {
        this(
            rentals, checkIns, files, scope, notifications, json, returns,
            Clock.systemUTC(), new IntakePolicyProperties(), null, null, null
        );
    }

    @Autowired
    public IntakeWorkflowService(
        RentalRepository rentals,
        CheckInRepository checkIns,
        FileAssetRepository files,
        FacilityScopeService scope,
        NotificationService notifications,
        ObjectMapper json,
        ReturnCaseRepository returns,
        Clock clock,
        IntakePolicyProperties policy,
        IntakeVerificationAttemptRepository verificationAttempts,
        IntakeIdempotencyRecordRepository idempotencyRecords,
        MaintenanceTaskRepository maintenanceTasks
    ) {
        this.rentals = rentals;
        this.checkIns = checkIns;
        this.files = files;
        this.scope = scope;
        this.notifications = notifications;
        this.json = json;
        this.returns = returns;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.policy = policy != null ? policy : new IntakePolicyProperties();
        this.timeline = new IntakeTimelineCalculator(this.policy);
        this.verificationAttempts = verificationAttempts;
        this.idempotencyRecords = idempotencyRecords;
        this.maintenanceTasks = maintenanceTasks;
    }

    @Transactional
    public void readyForReturn(ActorPrincipal actor, UUID id) {
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận bàn giao.");
        var rc = returns.findByIdForUpdate(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy hồ sơ trả kho."));
        scope.assertCanOperate(actor, rc.getFacility().getId());
        if (!List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(rc.getStatus()))
            throw ApiExceptions.conflict("Hồ sơ không còn ở bước chờ nghiệm thu.");
        if (rc.getReadyForInspectionAt() == null) rc.setReadyForInspectionAt(clock.instant());
        returns.saveAndFlush(rc);
    }

    public record Command(
        String action,
        Instant at,
        String reason,
        String documentNumber,
        UUID photoId,
        Integer extensionDays,
        String idempotencyKey,
        Boolean keepAppointment,
        Instant appointmentAt,
        Instant actualArrivedAt,
        String evidenceNote,
        String outcome,
        Long expectedVersion
    ) {
        public Command(String action, Instant at, String reason, String documentNumber, UUID photoId) {
            this(action, at, reason, documentNumber, photoId, null, null, null, null, null, null, null, null);
        }

        public Command(String action, Instant at, String reason, String documentNumber, UUID photoId, Integer extensionDays) {
            this(action, at, reason, documentNumber, photoId, extensionDays, null, null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> read(ActorPrincipal actor, UUID reservationId) {
        Rental r = rentals.findByReservation_Id(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Chưa có hồ sơ thuê."));
        access(actor, r);
        return view(r);
    }

    @Transactional
    public Map<String, Object> change(ActorPrincipal actor, UUID reservationId, Command command) {
        Rental r = rentals.findByReservationIdForUpdate(reservationId)
            .orElseThrow(() -> ApiExceptions.notFound("Chưa có hồ sơ thuê."));
        access(actor, r);
        if (command == null || command.action() == null) {
            throw ApiExceptions.validation("Vui lòng chọn thao tác.", null);
        }
        if (command.expectedVersion() != null && !Objects.equals(command.expectedVersion(), r.getIntakeVersion())) {
            throw ApiExceptions.conflict("Dữ liệu hồ sơ vừa thay đổi. Vui lòng tải lại.");
        }
        String idemKey = clean(command.idempotencyKey());
        if (idemKey != null && idempotencyRecords != null) {
            var existing = idempotencyRecords.findByRental_IdAndActionAndIdempotencyKey(
                r.getId(), command.action(), idemKey
            );
            if (existing.isPresent()) {
                return deserializeOrView(existing.get().getResponseJson(), r);
            }
        }
        boolean allowAfterTerminal = "FACILITY_EXTENSION".equals(command.action())
            && actor.hasRole(RoleCode.MANAGER)
            && r.getStatus() == RentalStatus.return_requested;
        if (!List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus()) && !allowAfterTerminal) {
            throw ApiExceptions.invalidState("Hồ sơ không còn ở bước setup hoặc kiểm kê.");
        }

        Instant now = clock.instant();
        Instant before = null;
        Instant after = command.at();
        String notificationTitle = "Hồ sơ nhận kho đã cập nhật";
        String notificationContent = "Lịch hẹn hoặc tiến trình xử lý đã được cập nhật. Vui lòng xem chi tiết hồ sơ.";

        switch (command.action()) {
            case "COMPLETE_SETUP" -> {
                customer(actor, r);
                if (r.getMoveInCompletedAt() != null) {
                    return view(r);
                }
                if (timeline.isUnifiedPolicy(r) && !timeline.canDeclareSetupComplete(r, now)) {
                    throw ApiExceptions.setupCutoffExceeded(
                        "Đã quá mốc báo hoàn tất setup (6 giờ trước lịch hẹn hoặc hạn đến kiểm kê)."
                    );
                }
                r.setMoveInCompletedAt(now);
                r.setInventoryPending(true);
                after = now;
                checkIns.findByReservation_Id(reservationId).ifPresent(c -> {
                    if (c.getStatus() == CheckInStatus.setup_in_progress) {
                        c.setStatus(CheckInStatus.setup_completed);
                        c.setSetupCompletedAt(now);
                        checkIns.saveAndFlush(c);
                    }
                });
                notificationTitle = "Khách đã báo chuẩn bị hàng xong";
                notificationContent = "Đơn " + code(r) + " đã báo hoàn tất setup lúc " + VN_TIME.format(now) + ".";
            }
            case "EXTEND_SETUP" -> {
                customer(actor, r);
                Instant currentD = timeline.arrivalDeadline(r);
                Instant requestedD = resolveRequestedExtensionDeadline(r, command, currentD);
                if (Objects.equals(requestedD, currentD)) return view(r);

                if (timeline.isUnifiedPolicy(r)) {
                    timeline.validateExtension(r, now, requestedD);
                } else if (!SetupCompletionPolicy.canExtend(r, now, requestedD)) {
                    throw ApiExceptions.conflict("Không thể dời hạn này. Vui lòng kiểm tra thời hạn và số lần còn lại.");
                }
                before = currentD;
                after = requestedD;
                r.setInventoryFinalDeadlineAt(requestedD);
                r.setSetupCompletionDeadlineAt(requestedD);
                r.setSetupExtensionCount((r.getSetupExtensionCount() == null ? 0 : r.getSetupExtensionCount()) + 1);
                r.setInventoryDeadlineReminderAt(null);

                // Keep or optionally reschedule active appointment in same transaction.
                if (Boolean.FALSE.equals(command.keepAppointment()) && command.appointmentAt() != null) {
                    scheduleAppointmentInternal(r, reservationId, now, command.appointmentAt());
                } else if (IntakeTimelineCalculator.APPT_ACTIVE.equals(r.getAppointmentStatus())
                    && r.getInventoryAppointmentAt() != null
                    && r.getInventoryAppointmentAt().isAfter(requestedD)) {
                    r.setAppointmentStatus(IntakeTimelineCalculator.APPT_CANCELLED);
                }
                notificationTitle = "Đã gia hạn thời gian đến kiểm kê";
                notificationContent = "Đơn " + code(r) + " đã gia hạn đến " + VN_TIME.format(requestedD) + " (giờ Việt Nam).";
            }
            case "APPOINTMENT" -> {
                customer(actor, r);
                Instant requestedA = command.appointmentAt() != null ? command.appointmentAt() : command.at();
                before = scheduleAppointmentInternal(r, reservationId, now, requestedA);
                if (Objects.equals(before, requestedA) && IntakeTimelineCalculator.APPT_ACTIVE.equals(r.getAppointmentStatus())) {
                    return view(r);
                }
                after = requestedA;
                notificationTitle = before == null ? "Đã đặt lịch kiểm kê" : "Đã đổi lịch kiểm kê";
                notificationContent = "Đơn " + code(r) + " có lịch kiểm kê lúc " + VN_TIME.format(requestedA) + " (giờ Việt Nam).";
            }
            case "ARRIVAL" -> {
                staff(actor, r);
                Instant arrivedAt = command.actualArrivedAt() != null ? command.actualArrivedAt() : now;
                if (r.getActualArrivedAt() != null || r.getInventoryArrivedAt() != null) {
                    return view(r);
                }
                boolean backdated = command.actualArrivedAt() != null && command.actualArrivedAt().isBefore(now.minusSeconds(60));
                if (backdated && clean(command.evidenceNote()) == null && command.photoId() == null) {
                    throw ApiExceptions.validation("Ghi nhận đến muộn bắt buộc phải có bằng chứng (ghi chú nguồn hoặc ảnh).", null);
                }
                Instant d = timeline.arrivalDeadline(r);
                if (timeline.isUnifiedPolicy(r)) {
                    Instant receivedAt = timeline.intakeReceivedAt(r);
                    if (receivedAt != null && arrivedAt.isBefore(receivedAt)) {
                        throw ApiExceptions.validation("Thời điểm đến không thể trước thời điểm nhận kho.", null);
                    }
                    if (d != null && arrivedAt.isAfter(d)
                        && !IntakeTimelineCalculator.REVIEW_APPROVED_ON_TIME.equals(r.getAttendanceReviewStatus())) {
                        throw ApiExceptions.deadlineExpired("Khách đến sau hạn đến kiểm kê (" + VN_TIME.format(d) + ").");
                    }
                    if (IntakeTimelineCalculator.REVIEW_PENDING.equals(r.getAttendanceReviewStatus())
                        && r.getAttendanceReviewDeadlineAt() != null
                        && now.isAfter(r.getAttendanceReviewDeadlineAt())) {
                        throw ApiExceptions.deadlineExpired("Đã hết thời hạn đối soát phút cuối, cần nhân viên hoặc quản lý kết luận.");
                    }
                    if (r.getMoveInCompletedAt() == null) {
                        throw ApiExceptions.setupCutoffExceeded("Khách hàng cần báo setup xong trước khi tiếp nhận kiểm kê.");
                    }
                    if (r.getInventoryAppointmentAt() != null
                        && r.getMoveInCompletedAt().plus(policy.getSetupCutoffHours(), ChronoUnit.HOURS).isAfter(now)) {
                        throw ApiExceptions.setupCutoffExceeded("Chưa đủ thời gian chuẩn bị 6 giờ trước kiểm kê thực tế.");
                    }
                } else {
                    if (r.getMoveInCompletedAt() == null || d == null
                        || !now.isBefore(d) || r.getInventoryEscalatedAt() != null) {
                        throw ApiExceptions.conflict("Hồ sơ không còn trong hạn tiếp nhận kiểm kê.");
                    }
                }
                r.setActualArrivedAt(arrivedAt);
                r.setArrivalRecordedAt(now);
                r.setArrivalRecordedBy(actor.userId());
                r.setAttendanceEvidenceNote(clean(command.evidenceNote()));
                r.setAttendanceEvidenceFileId(command.photoId());
                r.setInventoryArrivedAt(arrivedAt);
                r.setAppointmentStatus(IntakeTimelineCalculator.APPT_ATTENDED);
                // The 72h completion window starts when the customer is confirmed present.
                timeline.startInspectionClock(r, arrivedAt);
                if (IntakeTimelineCalculator.REVIEW_PENDING.equals(r.getAttendanceReviewStatus())) {
                    r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_APPROVED_ON_TIME);
                }
                checkIns.findByReservation_Id(reservationId).ifPresent(c -> {
                    c.setInventoryArrivedAt(arrivedAt);
                    checkIns.saveAndFlush(c);
                });
                after = arrivedAt;
            }
            case "VERIFY" -> {
                staff(actor, r);
                if (r.getInventoryArrivedAt() == null && r.getActualArrivedAt() == null) {
                    throw ApiExceptions.invalidState("Vui lòng ghi nhận khách đã đến.");
                }
                if (timeline.isUnifiedPolicy(r) && r.getMoveInCompletedAt() == null) {
                    throw ApiExceptions.setupCutoffExceeded("Khách hàng chưa hoàn tất báo setup.");
                }
                String outcome = normalizeVerifyOutcome(command.outcome());
                if ("TECH_ERROR".equals(outcome)) {
                    recordTechnicalVerificationError(r, actor, now, command);
                    rentals.saveAndFlush(r);
                    return saveIdempotencyAndReturn(r, command.action(), idemKey);
                }
                if (IntakeTimelineCalculator.VERIF_PASSED.equals(r.getVerificationStatus())) {
                    throw ApiExceptions.invalidState("Hồ sơ đã xác minh danh tính thành công, không thể xác minh lại thông thường.");
                }
                if (r.getVerificationAttemptCount() >= 2
                    || IntakeTimelineCalculator.VERIF_FAILED_MAX_ATTEMPTS.equals(r.getVerificationStatus())) {
                    throw ApiExceptions.verificationLimit("Đã hết tối đa 2 lần xác minh danh tính.");
                }
                if (timeline.hasStartedInspectionClock(r)
                    && r.getInspectionCompletionDeadlineAt() != null
                    && now.isAfter(r.getInspectionCompletionDeadlineAt())) {
                    throw ApiExceptions.deadlineExpired("Đã quá hạn hoàn tất kiểm kê 72 giờ.");
                }

                CheckIn c = checkIns.findByReservation_Id(reservationId).orElse(null);
                boolean docMatch = matchesIdentityDocument(r, command.documentNumber());
                FileAsset validPhoto = validateVerificationPhoto(r, c, actor, command.photoId());

                if ("PASS".equals(outcome)) {
                    if (!docMatch) {
                        throw ApiExceptions.conflict("Giấy tờ không khớp người đăng ký thuê.");
                    }
                    if (validPhoto == null) {
                        throw ApiExceptions.conflict("Vui lòng tải ảnh tại buổi kiểm kê vào đúng hồ sơ.");
                    }
                } else {
                    if (clean(command.reason()) == null) {
                        throw ApiExceptions.validation("Vui lòng nhập lý do xác minh thất bại.", null);
                    }
                }

                // Atomic initialization of V; the 72h clock normally already started on arrival.
                if (r.getFirstIdentityVerificationAt() == null) {
                    r.setFirstIdentityVerificationAt(now);
                    // Legacy records have no arrival anchor, so verification still starts their window.
                    if (r.getInspectionClockStartedAt() == null) {
                        timeline.startInspectionClock(r, now);
                    }
                }
                int nextAttempt = r.getVerificationAttemptCount() + 1;
                r.setVerificationAttemptCount(nextAttempt);
                if (verificationAttempts != null) {
                    IntakeVerificationAttempt attempt = new IntakeVerificationAttempt();
                    attempt.setRental(r);
                    attempt.setSubstantiveOrdinal(nextAttempt);
                    attempt.setOutcome(outcome);
                    attempt.setRecordedAt(now);
                    attempt.setStaffId(actor.userId());
                    attempt.setDocumentNumberMasked(maskDoc(command.documentNumber()));
                    attempt.setEvidenceFileId(validPhoto != null ? validPhoto.getId() : command.photoId());
                    attempt.setReason(clean(command.reason()));
                    attempt.setIdempotencyKey(idemKey);
                    verificationAttempts.saveAndFlush(attempt);
                }

                if ("PASS".equals(outcome)) {
                    r.setVerificationStatus(IntakeTimelineCalculator.VERIF_PASSED);
                    r.setInventoryIdentityPhotoId(validPhoto.getId());
                    r.setInventoryIdentityVerifiedAt(now);
                    r.setInventoryIdentityVerifiedBy(actor.userId());
                    if (r.getInspectionStartedAt() == null) {
                        r.setInspectionStartedAt(now);
                    }
                    if (c != null) {
                        c.setInventoryIdentityPhotoId(validPhoto.getId());
                        c.setInventoryIdentityVerifiedAt(now);
                        c.setInventoryIdentityVerifiedBy(validPhoto.getUploadedBy());
                        checkIns.saveAndFlush(c);
                    }
                    notificationTitle = "Xác minh danh tính thành công";
                    notificationContent = "Đơn " + code(r) + " đã xác minh thành công. Hạn hoàn tất kiểm kê: "
                        + VN_TIME.format(r.getInspectionCompletionDeadlineAt()) + ".";
                } else {
                    if (nextAttempt >= 2) {
                        r.setVerificationStatus(IntakeTimelineCalculator.VERIF_FAILED_MAX_ATTEMPTS);
                        if (policy.isImmediateFailOnSecondVerificationFailure()) {
                            r.setInspectionFailureReason(clean(command.reason()) != null
                                ? clean(command.reason()) : "Xác minh danh tính thất bại 2 lần.");
                            createRecoveryTaskIfMissing(r, now, "Kiểm kê không thành công: thất bại xác minh danh tính 2 lần");
                        }
                        notificationTitle = "Xác minh danh tính thất bại lần 2";
                        notificationContent = "Đơn " + code(r) + " đã hết 2 lượt xác minh danh tính.";
                    } else {
                        r.setVerificationStatus(IntakeTimelineCalculator.VERIF_IN_PROGRESS);
                        notificationTitle = "Xác minh danh tính lần 1 chưa đạt";
                        notificationContent = "Đơn " + code(r) + " còn 1 lượt xác minh. Hạn hoàn tất 72 giờ đến "
                            + VN_TIME.format(r.getInspectionCompletionDeadlineAt()) + ".";
                    }
                }
                after = now;
            }
            case "MARK_MISSED" -> {
                staff(actor, r);
                if (!IntakeTimelineCalculator.APPT_ACTIVE.equals(r.getAppointmentStatus())
                    || r.getInventoryAppointmentAt() == null) {
                    throw ApiExceptions.invalidState("Hồ sơ không có lịch hẹn đang hoạt động để đánh dấu bỏ lỡ.");
                }
                if (r.getActualArrivedAt() != null || r.getInventoryArrivedAt() != null) {
                    throw ApiExceptions.invalidState("Khách hàng đã được ghi nhận có mặt.");
                }
                Instant d = timeline.arrivalDeadline(r);
                Instant waitEnd = r.getInventoryAppointmentAt().plus(policy.getMissedWaitMinutes(), ChronoUnit.MINUTES);
                if (d != null && waitEnd.isAfter(d)) waitEnd = d;
                if (now.isBefore(waitEnd)) {
                    throw ApiExceptions.conflict("Cần chờ hết thời gian chờ tối đa trước khi xác nhận bỏ lỡ lịch.");
                }
                r.setAppointmentStatus(IntakeTimelineCalculator.APPT_MISSED);
                r.setLastMissedReminderAt(now);
                after = now;
                notificationTitle = "Khách bỏ lỡ lịch hẹn kiểm kê";
                notificationContent = "Đơn " + code(r) + " đã lỡ lịch hẹn. Vui lòng đặt lại lịch kiểm kê mới trước hạn.";
            }
            case "RESOLVE_REVIEW" -> {
                manager(actor, r);
                if (!IntakeTimelineCalculator.REVIEW_PENDING.equals(r.getAttendanceReviewStatus())) {
                    throw ApiExceptions.invalidState("Hồ sơ không ở trạng thái chờ đối soát.");
                }
                String resolution = normalizeReviewOutcome(command.outcome());
                r.setAttendanceReviewOwnerId(actor.userId());
                if ("APPROVED_ON_TIME".equals(resolution)) {
                    if (r.getMoveInCompletedAt() == null) {
                        throw ApiExceptions.setupCutoffExceeded(
                            "Khách hàng chưa hoàn tất báo setup trước hạn; không thể duyệt đến đúng hạn (chỉ có thể chốt vắng mặt hoặc chuyển ngoại lệ cơ sở nếu có sự cố kỹ thuật/cơ sở)."
                        );
                    }
                    if (clean(command.reason()) == null) {
                        throw ApiExceptions.validation("Vui lòng nhập lý do và bằng chứng chấp nhận đến đúng hạn.", null);
                    }
                    r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_APPROVED_ON_TIME);
                    if (r.getActualArrivedAt() == null && command.actualArrivedAt() != null) {
                        r.setActualArrivedAt(command.actualArrivedAt());
                        r.setInventoryArrivedAt(command.actualArrivedAt());
                        r.setArrivalRecordedAt(now);
                        r.setArrivalRecordedBy(actor.userId());
                    }
                    notificationTitle = "Đã duyệt đối soát đến đúng hạn";
                    notificationContent = "Đơn " + code(r) + " được xác nhận đến đúng hạn. Có thể tiếp tục xác minh danh tính.";
                } else if ("CONFIRMED_ABSENT".equals(resolution)) {
                    r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_CONFIRMED_ABSENT);
                    r.setInspectionFailureReason(clean(command.reason()) != null
                        ? clean(command.reason()) : "Không đến kiểm kê trong hạn.");
                    createRecoveryTaskIfMissing(r, now, "Quá hạn đến kiểm kê (đã đối soát vắng mặt)");
                    notificationTitle = "Đã chốt đối soát: khách không đến";
                    notificationContent = "Đơn " + code(r) + " đã chuyển sang nhiệm vụ thu hồi chờ xác nhận.";
                } else {
                    if (clean(command.reason()) == null) {
                        throw ApiExceptions.validation("Vui lòng nhập lý do ngoại lệ cơ sở.", null);
                    }
                    r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_FACILITY_EXCEPTION);
                    r.setExceptionOwnerId(actor.userId());
                    r.setExceptionReason(clean(command.reason()));
                    if (command.at() != null && command.at().isAfter(now)) {
                        r.setExceptionDueAt(command.at());
                        r.setInventoryFinalDeadlineAt(command.at());
                        r.setSetupCompletionDeadlineAt(command.at());
                    }
                    notificationTitle = "Chuyển ngoại lệ do cơ sở";
                    notificationContent = "Đơn " + code(r) + " đã chuyển Manager xử lý ngoại lệ cơ sở.";
                }
                after = now;
            }
            case "INTERRUPT_INSPECTION" -> {
                staff(actor, r);
                if (!IntakeTimelineCalculator.VERIF_PASSED.equals(r.getVerificationStatus())) {
                    throw ApiExceptions.invalidState("Chỉ ghi nhận gián đoạn khi đã xác minh danh tính PASS.");
                }
                if (clean(command.reason()) == null) {
                    throw ApiExceptions.validation("Vui lòng ghi rõ nguyên nhân gián đoạn.", null);
                }
                r.setInterruptionReason(clean(command.reason()));
                after = now;
            }
            case "COMPLETE_INSPECTION" -> {
                staff(actor, r);
                if (!IntakeTimelineCalculator.VERIF_PASSED.equals(r.getVerificationStatus())) {
                    throw ApiExceptions.invalidState("Cần xác minh danh tính PASS trước khi hoàn tất kiểm kê.");
                }
                if (!timeline.isWithinCompletionDeadline(r, now)) {
                    throw ApiExceptions.deadlineExpired("Đã quá hạn hoàn tất kiểm kê 72 giờ.");
                }
                r.setInspectionCompletedAt(now);
                r.setInventoryVerifiedAt(now);
                r.setInventoryPending(false);
                after = now;
                notificationTitle = "Đã hoàn tất kiểm kê hàng hóa";
                notificationContent = "Đơn " + code(r) + " đã hoàn tất kiểm kê trong hạn 72 giờ.";
            }
            case "FACILITY_EXTENSION" -> {
                manager(actor, r);
                if (command.reason() == null || command.reason().isBlank() || command.at() == null || !command.at().isAfter(now))
                    throw ApiExceptions.validation("Vui lòng nhập lý do và thời hạn mới.", null);
                if (r.getInventoryEscalatedAt() != null) throw ApiExceptions.conflict("Hồ sơ đã chuyển thu hồi, cần xử lý nhiệm vụ hiện có.");
                if (timeline.hasStartedInspectionClock(r) && r.getInspectionCompletionDeadlineAt() != null) {
                    before = r.getInspectionCompletionDeadlineAt();
                    if (!command.at().isAfter(before)) throw ApiExceptions.validation("Hạn mới phải sau hạn hiện tại.", null);
                    r.setInspectionCompletionDeadlineAt(command.at());
                } else if (r.getInventoryRemovalPublishedAt() != null) {
                    before = r.getInventoryRemovalDeadlineAt();
                    if (!command.at().isAfter(before)) throw ApiExceptions.validation("Hạn mới phải sau hạn hiện tại.", null);
                    r.setInventoryRemovalDeadlineAt(command.at());
                    returns.findByRental_Id(r.getId()).stream()
                        .filter(rc -> List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled).contains(rc.getStatus()))
                        .forEach(rc -> { rc.setRecoveryEligibleAt(command.at()); returns.save(rc); });
                } else if (r.getInventoryRemainderDeadlineAt() != null) {
                    throw ApiExceptions.conflict("Hồ sơ dùng phương án cũ. Vui lòng xử lý kết quả kiểm kê trước khi dời hạn.");
                } else {
                    before = timeline.arrivalDeadline(r);
                    if (before != null && !command.at().isAfter(before)) throw ApiExceptions.validation("Hạn mới phải sau hạn hiện tại.", null);
                    r.setInventoryFinalDeadlineAt(command.at());
                    r.setSetupCompletionDeadlineAt(command.at());
                }
                r.setExceptionOwnerId(actor.userId());
                r.setExceptionReason(clean(command.reason()));
                r.setExceptionDueAt(command.at());
            }
            case "REMIND" -> {
                if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER)) throw ApiExceptions.forbidden("Không có quyền nhắc khách.");
                scope.assertCanOperate(actor, r.getFacility().getId());
                String key = "REMIND_" + (actor.hasRole(RoleCode.MANAGER) ? "MANAGER" : "STAFF");
                for (Map<String, Object> h : history(r)) {
                    if (key.equals(h.get("action")) && Instant.parse((String) h.get("time")).plus(2, ChronoUnit.HOURS).isAfter(now))
                        throw ApiExceptions.conflict("Vui lòng chờ đủ 2 giờ trước khi nhắc lại.");
                }
                append(r, actor, key, null, null, null, now);
                notifications.createNotification(r.getCustomer().getId(), NotificationType.CHECKIN,
                    "Nhắc lịch kiểm kê", "Vui lòng mở hồ sơ để kiểm tra lịch hẹn và thời hạn kiểm kê.", reservationId);
                rentals.saveAndFlush(r);
                return view(r);
            }
            default -> throw ApiExceptions.validation("Thao tác không hợp lệ.", null);
        }

        r.setIntakeVersion(r.getIntakeVersion() + 1);
        append(r, actor, command.action(), before, after, command.reason(), now);
        rentals.saveAndFlush(r);
        notifications.createNotification(r.getCustomer().getId(), NotificationType.CHECKIN, notificationTitle, notificationContent, reservationId);
        notifications.createFacilityOperationsNotifications(r.getFacility().getId(), NotificationType.CHECKIN,
            notificationTitle, notificationContent, reservationId);
        return saveIdempotencyAndReturn(r, command.action(), idemKey);
    }

    private Instant resolveRequestedExtensionDeadline(Rental r, Command command, Instant currentD) {
        if (command.at() != null) return command.at();
        if (command.extensionDays() != null && currentD != null) {
            return currentD.plus(command.extensionDays() * 24L, ChronoUnit.HOURS);
        }
        return null;
    }

    private Instant scheduleAppointmentInternal(Rental r, UUID reservationId, Instant now, Instant requestedA) {
        if (timeline.isUnifiedPolicy(r)) {
            timeline.validateAppointment(r, now, requestedA);
        } else {
            if (r.getMoveInCompletedAt() == null || r.getInventoryArrivedAt() != null
                || r.getInventoryRemovalPublishedAt() != null || r.getInventoryEscalatedAt() != null
                || r.getInventoryFinalDeadlineAt() == null || requestedA == null
                || requestedA.isBefore(now) || !requestedA.isBefore(r.getInventoryFinalDeadlineAt()))
                throw ApiExceptions.conflict("Vui lòng chọn lịch kiểm kê còn trong thời hạn.");
        }

        CheckIn checkIn = checkIns.findByReservation_Id(reservationId).orElse(null);
        Instant before = r.getInventoryAppointmentAt() != null
            ? r.getInventoryAppointmentAt()
            : checkIn == null ? null : checkIn.getInventoryAppointmentAt();
        if (Objects.equals(before, requestedA)
            && (!timeline.isUnifiedPolicy(r) || IntakeTimelineCalculator.APPT_ACTIVE.equals(r.getAppointmentStatus()))) {
            return before;
        }

        if (policy.getAppointmentSlotCapacity() > 0 && r.getFacility() != null) {
            Instant slotStart = requestedA.truncatedTo(ChronoUnit.HOURS);
            Instant slotEnd = slotStart.plus(1, ChronoUnit.HOURS);
            long booked = rentals.countActiveAppointmentsInSlot(r.getFacility().getId(), r.getId(), slotStart, slotEnd);
            if (booked >= policy.getAppointmentSlotCapacity()) {
                throw ApiExceptions.noSlot("Khung giờ này đã đầy lịch tiếp nhận kiểm kê. Vui lòng chọn giờ khác.");
            }
        }

        r.setInventoryAppointmentAt(requestedA);
        r.setAppointmentStatus(IntakeTimelineCalculator.APPT_ACTIVE);
        r.setAppointmentVersion(r.getAppointmentVersion() + 1);
        r.setLastMissedReminderAt(null);
        if (checkIn != null) {
            checkIn.setInventoryAppointmentAt(requestedA);
            checkIns.saveAndFlush(checkIn);
        }
        return before;
    }

    private void recordTechnicalVerificationError(Rental r, ActorPrincipal actor, Instant now, Command command) {
        String reason = clean(command.reason()) != null ? clean(command.reason()) : "Lỗi kỹ thuật camera/hệ thống khi xác minh.";
        if (verificationAttempts != null) {
            IntakeVerificationAttempt attempt = new IntakeVerificationAttempt();
            attempt.setRental(r);
            attempt.setSubstantiveOrdinal(null);
            attempt.setOutcome("TECH_ERROR");
            attempt.setRecordedAt(now);
            attempt.setStaffId(actor.userId());
            attempt.setReason(reason);
            attempt.setIdempotencyKey(clean(command.idempotencyKey()));
            verificationAttempts.saveAndFlush(attempt);
        }
        r.setExceptionReason(reason);
        append(r, actor, "VERIFY_TECH_ERROR", null, now, reason, now);
        notifications.createFacilityOperationsNotifications(r.getFacility().getId(), NotificationType.CHECKIN,
            "Lỗi kỹ thuật khi xác minh danh tính",
            "Đơn " + code(r) + " gặp sự cố kỹ thuật khi xác minh (" + reason + "). Không trừ lượt xác minh.",
            r.getReservation().getId());
    }

    private void createRecoveryTaskIfMissing(Rental r, Instant now, String reason) {
        if (maintenanceTasks == null || r.getStorageUnit() == null) {
            r.setInventoryEscalatedAt(now);
            return;
        }
        boolean alreadyOpen = r.getId() != null && maintenanceTasks.findByRental_IdAndTaskTypeAndStatusIn(
            r.getId(), MaintenanceTaskType.maintenance,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        ).stream().anyMatch(t -> t.getRecoveryType() == MaintenanceRecoveryType.INVENTORY_OVERDUE);
        if (!alreadyOpen) {
            MaintenanceTask task = new MaintenanceTask();
            task.setFacility(r.getFacility());
            task.setStorageUnit(r.getStorageUnit());
            task.setRental(r);
            task.setTaskType(MaintenanceTaskType.maintenance);
            task.setRecoveryType(MaintenanceRecoveryType.INVENTORY_OVERDUE);
            task.setTitle("Thu hồi · " + reason);
            task.setReason(reason + ". Không tự di chuyển hàng hoặc ghi nhận kho trống.");
            task.setPriority(TaskPriority.high);
            task.setStatus(MaintenanceTaskStatus.open);
            task.setDeadlineAt(now.plus(24, ChronoUnit.HOURS));
            task.setDueAt(now.atZone(BusinessTime.ZONE).toLocalDate());
            maintenanceTasks.saveAndFlush(task);
        }
        r.setInventoryEscalatedAt(now);
    }

    private boolean matchesIdentityDocument(Rental r, String supplied) {
        String declared = r.getReservation() == null ? null : r.getReservation().getIdentityDocumentNumber();
        if (declared == null || supplied == null) return false;
        return declared.replaceAll("\\s", "").equalsIgnoreCase(supplied.replaceAll("\\s", ""));
    }

    private FileAsset validateVerificationPhoto(Rental r, CheckIn c, ActorPrincipal actor, UUID photoId) {
        if (photoId == null || c == null) return null;
        var f = files.findById(photoId).orElse(null);
        Instant arrivalRef = r.getActualArrivedAt() != null ? r.getActualArrivedAt() : r.getInventoryArrivedAt();
        if (f == null || f.getStatus() != FileAssetStatus.ACTIVE || f.getContentType() == null
            || !f.getContentType().startsWith("image/") || !"CHECK_IN".equals(f.getEntityType())
            || !Objects.equals(c.getId(), f.getEntityId())
            || (arrivalRef != null && f.getCreatedAt().isBefore(arrivalRef))
            || !actor.userId().equals(f.getUploadedBy().getId())) {
            return null;
        }
        return f;
    }

    private String normalizeVerifyOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return "PASS";
        String upper = outcome.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PASS", "FAIL", "TECH_ERROR").contains(upper)) {
            throw ApiExceptions.validation("Kết quả xác minh phải là PASS, FAIL hoặc TECH_ERROR.", null);
        }
        return upper;
    }

    private String normalizeReviewOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            throw ApiExceptions.validation("Vui lòng chọn kết luận đối soát.", null);
        }
        String upper = outcome.trim().toUpperCase(Locale.ROOT);
        if (!List.of("APPROVED_ON_TIME", "CONFIRMED_ABSENT", "FACILITY_EXCEPTION").contains(upper)) {
            throw ApiExceptions.validation("Kết luận đối soát không hợp lệ.", null);
        }
        return upper;
    }

    private Map<String, Object> saveIdempotencyAndReturn(Rental r, String action, String idemKey) {
        Map<String, Object> payload = view(r);
        if (idemKey != null && idempotencyRecords != null) {
            try {
                IntakeIdempotencyRecord record = new IntakeIdempotencyRecord();
                record.setRental(r);
                record.setAction(action);
                record.setIdempotencyKey(idemKey);
                record.setResponseJson(json.writeValueAsString(payload));
                idempotencyRecords.saveAndFlush(record);
            } catch (Exception ignored) {
                // Concurrent duplicate insert is handled by unique constraint on retry.
            }
        }
        return payload;
    }

    private Map<String, Object> deserializeOrView(String raw, Rental r) {
        if (raw == null || raw.isBlank()) return view(r);
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return view(r);
        }
    }

    private String code(Rental r) {
        return r.getReservation() != null && r.getReservation().getReservationCode() != null
            ? r.getReservation().getReservationCode() : "";
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String maskDoc(String value) {
        String c = clean(value);
        if (c == null) return null;
        return c.length() <= 4 ? "****" : "*".repeat(c.length() - 4) + c.substring(c.length() - 4);
    }

    private void access(ActorPrincipal actor, Rental r) {
        if (actor.hasRole(RoleCode.CUSTOMER)) { customer(actor, r); return; }
        if (!actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER, RoleCode.BUSINESS, RoleCode.ADMIN))
            throw ApiExceptions.forbidden("Không có quyền xem hồ sơ.");
        scope.assertCanRead(actor, r.getFacility().getId());
    }

    private void customer(ActorPrincipal actor, Rental r) {
        if (!actor.hasRole(RoleCode.CUSTOMER) || !actor.userId().equals(r.getCustomer().getId()))
            throw ApiExceptions.forbidden("Không có quyền thao tác hồ sơ này.");
    }

    private void staff(ActorPrincipal actor, Rental r) {
        if (!actor.hasRole(RoleCode.STAFF)) throw ApiExceptions.forbidden("Chỉ Staff được ghi nhận buổi kiểm kê.");
        scope.assertCanOperate(actor, r.getFacility().getId());
    }

    private void manager(ActorPrincipal actor, Rental r) {
        if (!actor.hasRole(RoleCode.MANAGER)) throw ApiExceptions.forbidden("Chỉ Manager được xử lý thao tác này.");
        scope.assertCanManage(actor, r.getFacility().getId());
    }

    private List<Map<String, Object>> history(Rental r) {
        try {
            return r.getWorkflowHistoryJson() == null
                ? new ArrayList<>()
                : json.readValue(r.getWorkflowHistoryJson(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw ApiExceptions.conflict("Lịch sử hồ sơ cần được đối soát.");
        }
    }

    private void append(Rental r, ActorPrincipal actor, String action, Instant before, Instant after, String reason, Instant now) {
        var list = history(r);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action);
        event.put("actor", actor.userId().toString());
        event.put("time", now.toString());
        event.put("before", before == null ? null : before.toString());
        event.put("after", after == null ? null : after.toString());
        event.put("reason", reason);
        list.add(event);
        try {
            r.setWorkflowHistoryJson(json.writeValueAsString(list));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save workflow history", e);
        }
    }

    private Map<String, Object> view(Rental r) {
        Map<String, Object> v = new LinkedHashMap<>();
        Instant arrivalD = timeline.arrivalDeadline(r);
        Instant setupCutoff = timeline.setupCutoff(r);
        v.put("policyVersion", r.getIntakePolicyVersion());
        v.put("receivedAt", r.getSetupStartedAt());
        v.put("originalDeadlineAt", r.getOriginalInspectionArrivalDeadlineAt());
        v.put("arrivalDeadlineAt", arrivalD);
        v.put("setupCutoffAt", setupCutoff);
        v.put("setupDeadlineAt", arrivalD);
        v.put("setupExtensionDays", r.getSetupExtensionDays());
        v.put("extensionMaxHours", timeline.maxExtensionHoursFor(r));
        v.put("maxExtensions", r.getIntakeExtensionMaxCount() != null ? r.getIntakeExtensionMaxCount() : policy.getMaxExtensions());
        v.put("setupExtensionCount", r.getSetupExtensionCount());
        v.put("readyAt", r.getMoveInCompletedAt());

        var checkIn = checkIns.findByReservation_Id(r.getReservation().getId()).orElse(null);
        Instant appointmentAt = r.getInventoryAppointmentAt() != null
            ? r.getInventoryAppointmentAt()
            : checkIn == null ? null : checkIn.getInventoryAppointmentAt();
        v.put("appointmentAt", appointmentAt);
        v.put("appointmentStatus", r.getAppointmentStatus());
        v.put("appointmentVersion", r.getAppointmentVersion());
        v.put("actualArrivedAt", r.getActualArrivedAt());
        v.put("arrivalRecordedAt", r.getArrivalRecordedAt());
        v.put("arrivedAt", r.getInventoryArrivedAt());
        v.put("attendanceReviewDeadlineAt", r.getAttendanceReviewDeadlineAt());
        v.put("attendanceReviewStatus", r.getAttendanceReviewStatus());
        v.put("attendanceReviewOwnerId", r.getAttendanceReviewOwnerId());
        v.put("firstIdentityVerificationAt", r.getFirstIdentityVerificationAt());
        v.put("inspectionClockStartedAt", timeline.inspectionClockStartedAt(r));
        v.put("inspectionCompletionDeadlineAt", r.getInspectionCompletionDeadlineAt());
        v.put("verificationAttemptCount", r.getVerificationAttemptCount());
        v.put("maxVerificationAttempts", 2);
        v.put("verificationStatus", r.getVerificationStatus());
        v.put("identityVerifiedAt", r.getInventoryIdentityVerifiedAt());
        v.put("inspectionStartedAt", r.getInspectionStartedAt());
        v.put("inspectionCompletedAt", r.getInspectionCompletedAt());
        v.put("inspectionFailureReason", r.getInspectionFailureReason());
        v.put("interruptionReason", r.getInterruptionReason());
        v.put("exceptionOwnerId", r.getExceptionOwnerId());
        v.put("exceptionDueAt", r.getExceptionDueAt());
        v.put("exceptionReason", r.getExceptionReason());
        v.put("inventoryDeadlineAt", arrivalD);
        v.put("resultPublishedAt", r.getInventoryResultPublishedAt());
        v.put("remainderDeadlineAt", r.getInventoryRemainderDeadlineAt());
        v.put("resultConfirmationDueAt", checkIn != null && checkIn.getInventoryResolutionStatus() == InventoryResolutionStatus.PENDING_CUSTOMER
            && r.getInventoryResultPublishedAt() != null ? r.getInventoryResultPublishedAt().plus(48, ChronoUnit.HOURS) : null);
        v.put("intakeVersion", r.getIntakeVersion());
        v.put("verificationAttempts", verificationAttempts == null || r.getId() == null
            ? List.of()
            : verificationAttempts.findByRental_IdOrderByRecordedAtAsc(r.getId()).stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("attemptNumber", a.getSubstantiveOrdinal());
                m.put("outcome", a.getOutcome());
                m.put("recordedAt", a.getRecordedAt());
                m.put("reason", a.getReason());
                return m;
            }).toList());
        v.put("history", history(r));
        return v;
    }
}
