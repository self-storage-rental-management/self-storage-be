package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.time.BusinessTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeDeadlineScheduler {
    private final RentalRepository rentals;
    private final CheckInRepository checkIns;
    private final MaintenanceTaskRepository tasks;
    private final ReturnCaseRepository returns;
    private final NotificationService notifications;
    private final UserRepository users;
    private final Clock clock;
    private final IntakePolicyProperties policy;
    private final IntakeTimelineCalculator timeline;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /** Legacy-compatible constructor for unit tests. */
    public IntakeDeadlineScheduler(
        RentalRepository rentals,
        CheckInRepository checkIns,
        MaintenanceTaskRepository tasks,
        ReturnCaseRepository returns,
        NotificationService notifications,
        UserRepository users
    ) {
        this(rentals, checkIns, tasks, returns, notifications, users, Clock.systemUTC(), new IntakePolicyProperties());
    }

    @Autowired
    public IntakeDeadlineScheduler(
        RentalRepository rentals,
        CheckInRepository checkIns,
        MaintenanceTaskRepository tasks,
        ReturnCaseRepository returns,
        NotificationService notifications,
        UserRepository users,
        Clock clock,
        IntakePolicyProperties policy
    ) {
        this.rentals = rentals;
        this.checkIns = checkIns;
        this.tasks = tasks;
        this.returns = returns;
        this.notifications = notifications;
        this.users = users;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.policy = policy != null ? policy : new IntakePolicyProperties();
        this.timeline = new IntakeTimelineCalculator(this.policy);
    }

    @Scheduled(fixedDelayString="${storagehub.intake-deadline.delay-ms:60000}", initialDelayString="${storagehub.intake-deadline.initial-delay-ms:60000}")
    @Transactional
    public void process() {
        Instant now = clock.instant();
        for (Rental r : rentals.findAll()) {
            if (r.getCompletedAt() != null || r.getActualReturnedAt() != null) continue;
            if (timeline.isUnifiedPolicy(r)) {
                processUnifiedRental(r, now);
            } else {
                processLegacyRental(r, now);
            }
            if (r.getInventoryResultPublishedAt() != null) {
                var c = checkIns.findByReservation_Id(r.getReservation().getId()).orElse(null);
                if (c != null && c.getInventoryResolutionStatus() == InventoryResolutionStatus.PENDING_CUSTOMER) {
                    if (!now.isBefore(r.getInventoryResultPublishedAt().plus(48, ChronoUnit.HOURS)))
                        notify(r, "Đã quá hạn xác nhận kiểm kê", "Kết quả đang chờ xác nhận. Vui lòng xem hồ sơ hoặc gửi ý kiến. Không tự động xác nhận thay bạn.");
                    else if (!now.isBefore(r.getInventoryResultPublishedAt().plus(24, ChronoUnit.HOURS)))
                        notify(r, "Nhắc xác nhận kết quả kiểm kê", "Vui lòng xác nhận hoặc gửi ý kiến về kết quả kiểm kê trong 48 giờ.");
                }
            }
            if (r.getInventoryRemovalDeadlineAt() != null && r.getInventoryEscalatedAt() == null
                && r.getActualReturnedAt() == null
                && !now.isBefore(r.getInventoryRemovalDeadlineAt()))
                recoverMismatchWithoutReturn(r, now);
        }
        processReturnAndTaskSlas(now);
    }

    private void processUnifiedRental(Rental r, Instant now) {
        if (!List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())) return;

        // Post-V phase: track only F (72h), never recover by arrival deadline D.
        if (r.getFirstIdentityVerificationAt() != null) {
            processCompletionWindow(r, now);
            return;
        }

        if (r.getInventoryEscalatedAt() != null || r.getInventoryRemovalPublishedAt() != null) return;
        Instant d = timeline.arrivalDeadline(r);
        if (d == null) return;

        // 1. D-48h reminder (versioned via inventoryDeadlineReminderAt cleared on extension).
        if (now.isBefore(d)
            && !now.plus(policy.getArrivalReminderHoursBefore(), ChronoUnit.HOURS).isBefore(d)
            && r.getInventoryDeadlineReminderAt() == null) {
            notifications.createNotification(
                r.getCustomer().getId(), NotificationType.CHECKIN,
                "Sắp hết hạn đến kiểm kê",
                "Hồ sơ còn dưới " + policy.getArrivalReminderHoursBefore() + " giờ trước hạn đến kiểm kê. Vui lòng hoàn tất setup và đặt lịch.",
                r.getReservation().getId()
            );
            r.setInventoryDeadlineReminderAt(now);
        }

        // 2. Active appointment cutoff (NOT_READY), reminders (A-48h, A), and wait window (A + 30m).
        if (IntakeTimelineCalculator.APPT_ACTIVE.equals(r.getAppointmentStatus())
            && r.getInventoryAppointmentAt() != null
            && r.getActualArrivedAt() == null && r.getInventoryArrivedAt() == null) {
            Instant a = r.getInventoryAppointmentAt();
            Instant c = a.minus(policy.getSetupCutoffHours(), ChronoUnit.HOURS);
            if (r.getMoveInCompletedAt() == null && now.isAfter(c)) {
                r.setAppointmentStatus(IntakeTimelineCalculator.APPT_NOT_READY);
                r.setAppointmentVersion(r.getAppointmentVersion() + 1);
                notify(r, "Lịch hẹn kiểm kê bị vô hiệu do chưa báo setup",
                    "Đã qua mốc 6 giờ trước lịch hẹn mà bạn chưa báo hoàn tất setup. Lịch cũ đã được hủy; vui lòng báo setup xong hoặc gia hạn nếu còn lượt.");
            } else {
                if (now.isBefore(a) && !now.plus(48, ChronoUnit.HOURS).isBefore(a)) {
                    notify(r, "Nhắc lịch hẹn kiểm kê (còn 48 giờ)",
                        "Bạn có lịch hẹn kiểm kê trong 48 giờ tới. Đừng quên báo setup xong trước giờ hẹn ít nhất 6 giờ.");
                }
                if (!now.isBefore(a)) {
                    notify(r, "Đã đến giờ hẹn kiểm kê",
                        "Đã đến giờ hẹn kiểm kê. Staff vui lòng đối chiếu và xác nhận khi khách có mặt.");
                }
                Instant waitEnd = a.plus(policy.getMissedWaitMinutes(), ChronoUnit.MINUTES);
                if (waitEnd.isAfter(d)) waitEnd = d;
                if (!now.isBefore(waitEnd)) {
                    notifications.createFacilityOperationsNotificationsOnce(
                        r.getFacility().getId(), NotificationType.CHECKIN,
                        "Cần xác nhận có mặt sau giờ hẹn",
                        "Đã qua " + policy.getMissedWaitMinutes() + " phút kể từ giờ hẹn. Staff vui lòng xác nhận khách có mặt hoặc đánh dấu bỏ lỡ.",
                        r.getReservation().getId()
                    );
                }
            }
        }

        // 3. MISSED reminders every 2 hours while D has not passed.
        if (IntakeTimelineCalculator.APPT_MISSED.equals(r.getAppointmentStatus())
            && now.isBefore(d)
            && r.getActualArrivedAt() == null && r.getInventoryArrivedAt() == null) {
            if (r.getLastMissedReminderAt() == null
                || !r.getLastMissedReminderAt().plus(policy.getMissedReminderIntervalHours(), ChronoUnit.HOURS).isAfter(now)) {
                notifications.createNotification(
                    r.getCustomer().getId(), NotificationType.CHECKIN,
                    "Nhắc đặt lại lịch kiểm kê",
                    "Bạn đã bỏ lỡ lịch kiểm kê trước đó. Vui lòng đặt lại lịch mới trước hạn "
                        + java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(BusinessTime.ZONE).format(d)
                        + " (giờ Việt Nam).",
                    r.getReservation().getId()
                );
                r.setLastMissedReminderAt(now);
            }
        }

        // 4. Past D (now > D) without V -> PENDING_ATTENDANCE_REVIEW (R = D + 30m).
        if (now.isAfter(d)) {
            if (r.getActualArrivedAt() != null && !r.getActualArrivedAt().isAfter(d)) {
                // Customer arrived on time; waiting for Staff verification / Manager attention.
                if (!now.isBefore(d.plus(policy.getAttendanceManagerEscalationMinutes(), ChronoUnit.MINUTES))) {
                    notifications.createFacilityOperationsNotificationsOnce(
                        r.getFacility().getId(), NotificationType.CHECKIN,
                        "Khách đã đến đúng hạn, chờ xác minh danh tính",
                        "Khách hàng đã có mặt trước hạn D nhưng chưa được xác minh danh tính. Manager vui lòng kiểm tra.",
                        r.getReservation().getId()
                    );
                }
                return;
            }
            if (r.getAttendanceReviewStatus() == null
                || IntakeTimelineCalculator.REVIEW_NONE.equals(r.getAttendanceReviewStatus())) {
                Instant rDeadline = timeline.reconciliationDeadline(d);
                r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_PENDING);
                r.setAttendanceReviewDeadlineAt(rDeadline != null ? rDeadline : d);
                notifications.createFacilityOperationsNotificationsOnce(
                    r.getFacility().getId(), NotificationType.CHECKIN,
                    "Cần đối soát khách đến phút cuối",
                    "Hồ sơ đã hết hạn đến kiểm kê. Vui lòng đối soát bằng chứng đến đúng hạn trước mốc R.",
                    r.getReservation().getId()
                );
                if (rDeadline != null && now.isAfter(rDeadline)) {
                    handleAttendanceReviewTimeout(r, now, d);
                }
            } else if (IntakeTimelineCalculator.REVIEW_PENDING.equals(r.getAttendanceReviewStatus())) {
                if (!now.isBefore(d.plus(policy.getAttendanceStaffReminderMinutes(), ChronoUnit.MINUTES))) {
                    notifications.createFacilityOperationsNotificationsOnce(
                        r.getFacility().getId(), NotificationType.CHECKIN,
                        "Nhắc đối soát hồ sơ quá hạn đến",
                        "Hồ sơ đang chờ đối soát phút cuối quá 15 phút.",
                        r.getReservation().getId()
                    );
                }
                Instant rDeadline = r.getAttendanceReviewDeadlineAt() != null ? r.getAttendanceReviewDeadlineAt() : d;
                if (now.isAfter(rDeadline)) {
                    handleAttendanceReviewTimeout(r, now, d);
                }
            }
        }
    }

    private void handleAttendanceReviewTimeout(Rental r, Instant now, Instant d) {
        boolean hasEvidence = r.getActualArrivedAt() != null
            || (r.getAttendanceEvidenceNote() != null && !r.getAttendanceEvidenceNote().isBlank())
            || r.getAttendanceEvidenceFileId() != null;
        if (!hasEvidence) {
            r.setAttendanceReviewStatus(IntakeTimelineCalculator.REVIEW_CONFIRMED_ABSENT);
            r.setInspectionFailureReason("Quá hạn đến kiểm kê và không có bằng chứng đến đúng hạn.");
            recovery(r, now, "Quá hạn đến kiểm kê");
            return;
        }
        notifications.createFacilityOperationsNotificationsOnce(
            r.getFacility().getId(), NotificationType.CHECKIN,
            "Chuyển Manager kết luận đối soát đến phút cuối",
            "Có bằng chứng đến đúng hạn nhưng chưa xác minh danh tính trước mốc R. Manager vui lòng tiếp nhận và kết luận.",
            r.getReservation().getId()
        );
        if (!now.isBefore(d.plus(policy.getAttendanceBoAlertMinutes(), ChronoUnit.MINUTES))) {
            for (User bo : users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE)) {
                notifications.createNotificationOnce(
                    bo.getId(), NotificationType.CHECKIN,
                    "Cảnh báo BO: hồ sơ đối soát chưa xử lý quá SLA",
                    "Hồ sơ có bằng chứng đến phút cuối chưa được Manager kết luận quá " + policy.getAttendanceBoAlertMinutes() + " phút.",
                    r.getReservation().getId()
                );
            }
        }
    }

    private void processCompletionWindow(Rental r, Instant now) {
        if (r.getInspectionCompletedAt() != null || r.getInventoryVerifiedAt() != null
            || r.getInventoryEscalatedAt() != null || r.getInventoryRemovalPublishedAt() != null) {
            return;
        }
        Instant f = r.getInspectionCompletionDeadlineAt();
        if (f == null) return;

        if (!now.isAfter(f)) {
            if (!now.plus(policy.getCompletionReminderSecondHoursBefore(), ChronoUnit.HOURS).isBefore(f)) {
                notify(r, "Sắp hết hạn hoàn tất kiểm kê (còn 6 giờ)",
                    "Hồ sơ kiểm kê còn dưới 6 giờ trong cửa sổ 72 giờ. Vui lòng hoàn tất xác minh và kiểm kê.");
            } else if (!now.plus(policy.getCompletionReminderFirstHoursBefore(), ChronoUnit.HOURS).isBefore(f)) {
                notify(r, "Nhắc hạn hoàn tất kiểm kê (còn 24 giờ)",
                    "Hồ sơ kiểm kê còn dưới 24 giờ trong cửa sổ 72 giờ.");
            }
            return;
        }

        // now > F: expired 72h completion window.
        if (r.getExceptionReason() != null && policy.isProtectFromAutoRecoveryOnFacilityException()) {
            notifications.createFacilityOperationsNotificationsOnce(
                r.getFacility().getId(), NotificationType.CHECKIN,
                "Quá hạn 72h đang xử lý ngoại lệ cơ sở",
                "Hồ sơ quá hạn 72 giờ do sự cố/ngoại lệ cơ sở. Không tự động thu hồi.",
                r.getReservation().getId()
            );
            return;
        }
        r.setInspectionFailureReason("Quá hạn hoàn tất kiểm kê 72 giờ.");
        recovery(r, now, "Quá hạn hoàn tất kiểm kê 72 giờ");
    }

    private void processLegacyRental(Rental r, Instant now) {
        if (r.getMoveInCompletedAt() == null && r.getSetupCompletionDeadlineAt() != null
            && List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())) {
            if (!now.isBefore(r.getSetupCompletionDeadlineAt())) recovery(r, now, "Quá hạn hoàn tất setup");
            else if (!now.plus(24, ChronoUnit.HOURS).isBefore(r.getSetupCompletionDeadlineAt()))
                notify(r, "Sắp hết hạn setup", "Vui lòng báo setup xong hoặc dời hạn trong số lượt cho phép.");
        }
        if (r.getMoveInCompletedAt() != null && r.isInventoryPending()
            && r.getInventoryArrivedAt() == null && r.getInventoryResultPublishedAt() == null
            && r.getInventoryRemovalPublishedAt() == null && r.getInventoryEscalatedAt() == null
            && r.getInventoryFinalDeadlineAt() != null && !now.isBefore(r.getInventoryFinalDeadlineAt())
            && List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())) {
            recovery(r, now, "Quá hạn đến kiểm kê");
        }
    }

    private void processReturnAndTaskSlas(Instant now) {
        for (ReturnCase rc : returns.findByStatusIn(List.of(ReturnCaseStatus.awaiting_business_review))) {
            if (!rc.isSetupExitRequested() || rc.getInspectedAt() == null) continue;
            if (now.isBefore(rc.getInspectedAt().plus(6, ChronoUnit.HOURS))
                && !now.isBefore(rc.getInspectedAt().plus(4, ChronoUnit.HOURS)))
                notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN,
                    "Sắp đến hạn duyệt quyết toán", "Bảng kê sắp hết hạn Manager duyệt 6 giờ. Vui lòng ưu tiên xử lý.", rc.getId());
            if (!now.isBefore(rc.getInspectedAt().plus(6, ChronoUnit.HOURS))) {
                notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN,
                    "Quá hạn duyệt quyết toán 6 giờ", "Bảng kê đang chờ Manager duyệt. Vui lòng ưu tiên xử lý hồ sơ.", rc.getId());
                for (User bo : users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE))
                    notifications.createNotificationOnce(bo.getId(), NotificationType.RETURN, "Quyết toán quá hạn duyệt",
                        "Có bảng kê đã quá hạn Manager duyệt 6 giờ. Vui lòng mở hồ sơ để theo dõi xử lý.", rc.getId());
            }
        }
        for (ReturnCase rc : returns.findByStatusIn(List.of(ReturnCaseStatus.awaiting_customer_confirmation))) {
            if (!rc.isSetupExitRequested() || rc.isCustomerConfirmed() || rc.getReturnType() == ReturnType.INVENTORY_MISMATCH) continue;
            Instant published = rc.getReviewedAt() != null ? rc.getReviewedAt() : rc.getInspectedAt();
            if (published == null || now.isBefore(published.plus(48, ChronoUnit.HOURS))) continue;
            String title = now.isBefore(published.plus(72, ChronoUnit.HOURS)) ? "Nhắc xác nhận quyết toán" : "Quá hạn xác nhận quyết toán";
            String content = "Vui lòng xem bảng kê và xác nhận hoặc gửi ý kiến. Hệ thống không tự đồng ý thay bạn.";
            notifications.createNotificationOnce(rc.getCustomer().getId(), NotificationType.RETURN, title, content, rc.getId());
            notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN, title, content, rc.getId());
        }
        for (ReturnCase rc : returns.findByStatusIn(List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled))) {
            if (rc.getReadyForInspectionAt() == null || rc.getReturnType() == ReturnType.INVENTORY_MISMATCH) continue;
            var due = com.storagehub.service.calendar.ServiceCalendarPolicy.addWorkingHours(rc.getReadyForInspectionAt(), 4);
            if (!now.isBefore(due.minus(2, ChronoUnit.HOURS)))
                notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN,
                    now.isBefore(due) ? "Sắp đến hạn nghiệm thu" : "Đã quá hạn nghiệm thu",
                    "Khách đã bàn giao đủ điều kiện. Vui lòng kiểm tra và hoàn tất biên bản nghiệm thu.", rc.getId());
        }
        for (MaintenanceTask t : tasks.findAll()) {
            if (t.getRecoveryType() == MaintenanceRecoveryType.MISMATCH_REMOVAL
                || t.getReturnCase() != null && t.getReturnCase().getReturnType() == ReturnType.INVENTORY_MISMATCH) continue;
            if (t.getRecoveryType() == null || t.getRecoveryType() == MaintenanceRecoveryType.NONE
                || t.getDeadlineAt() == null || !List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress).contains(t.getStatus())) continue;
            if (!now.isBefore(t.getDeadlineAt().minus(12, ChronoUnit.HOURS)))
                notifications.createFacilityOperationsNotificationsOnce(t.getFacility().getId(), NotificationType.CHECKIN,
                    now.isBefore(t.getDeadlineAt()) ? "Nhắc nhiệm vụ thu hồi" : "Nhiệm vụ thu hồi đã quá hạn",
                    "Vui lòng mở nhiệm vụ để phân công, ghi nhận bằng chứng và hoàn tất xử lý.", t.getId());
            if (!now.isBefore(t.getDeadlineAt()))
                for (User bo : users.findDistinctByRoles_CodeAndStatus(RoleCode.BUSINESS, UserStatus.ACTIVE))
                    notifications.createNotificationOnce(bo.getId(), NotificationType.CHECKIN, "Nhiệm vụ thu hồi đã quá hạn",
                        "Vui lòng mở hồ sơ nhiệm vụ để theo dõi xử lý.", t.getId());
        }
    }

    private void recoverMismatchWithoutReturn(Rental rental, Instant now) {
        // Reload under the same rental lock used by publication, extensions and return inspection.
        if (entityManager != null) entityManager.refresh(rental, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (rental.getInventoryEscalatedAt() != null || rental.getActualReturnedAt() != null
            || rental.getCompletedAt() != null || rental.getInventoryRemovalDeadlineAt() == null
            || now.isBefore(rental.getInventoryRemovalDeadlineAt())
            || !List.of(RentalStatus.setup, RentalStatus.active).contains(rental.getStatus())
            || returns.findByRental_Id(rental.getId()).stream().anyMatch(rc -> rc.getReturnType() == ReturnType.INVENTORY_MISMATCH))
            return;
        recovery(rental, now, "Quá hạn trả hàng do kiểm kê sai lệch");
    }

    private void notify(Rental r, String title, String content) {
        notifications.createNotificationOnce(r.getCustomer().getId(), NotificationType.CHECKIN, title, content, r.getReservation().getId());
        notifications.createFacilityOperationsNotificationsOnce(r.getFacility().getId(), NotificationType.CHECKIN, title, content, r.getReservation().getId());
    }

    private void recovery(Rental r, Instant now, String reason) {
        String title = "Thu hồi · " + reason;
        MaintenanceTaskType taskType = MaintenanceTaskType.maintenance;
        boolean existing = tasks.findByRental_IdAndTaskTypeAndStatusIn(r.getId(), taskType,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)).stream().anyMatch(t ->
                title.equals(t.getTitle()) || (r.getInventoryRemovalDeadlineAt() != null
                    && t.getRecoveryType() != null && t.getRecoveryType() != MaintenanceRecoveryType.NONE));
        if (existing) return;
        MaintenanceTask t = new MaintenanceTask();
        t.setRental(r); t.setFacility(r.getFacility()); t.setStorageUnit(r.getStorageUnit());
        t.setTitle(title); t.setReason(reason + ". Không tự di chuyển hàng hoặc ghi nhận kho trống.");
        t.setRecoveryType(r.getMoveInCompletedAt() == null
            ? MaintenanceRecoveryType.SETUP_OVERDUE : r.getInventoryRemovalPublishedAt() == null
                ? MaintenanceRecoveryType.INVENTORY_OVERDUE : MaintenanceRecoveryType.MISMATCH_REMOVAL);
        t.setTaskType(taskType); t.setStatus(MaintenanceTaskStatus.open); t.setPriority(TaskPriority.high);
        t.setDeadlineAt(now.plus(24, ChronoUnit.HOURS)); t.setDueAt(now.atZone(BusinessTime.ZONE).toLocalDate());
        tasks.saveAndFlush(t);
        r.setInventoryEscalatedAt(now); rentals.save(r);
        notify(r, reason, "Đã tạo nhiệm vụ xử lý. Hàng chưa được tự động di chuyển. Vui lòng liên hệ cơ sở.");
    }
}
