package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntakeDeadlineScheduler {
    private final RentalRepository rentals;
    private final CheckInRepository checkIns;
    private final MaintenanceTaskRepository tasks;
    private final ReturnCaseRepository returns;
    private final NotificationService notifications;
    private final UserRepository users;

    @Scheduled(fixedDelayString="${storagehub.intake-deadline.delay-ms:60000}", initialDelayString="${storagehub.intake-deadline.initial-delay-ms:60000}")
    @Transactional
    public void process() {
        Instant now = Instant.now();
        for (Rental r : rentals.findAll()) {
            if (r.getCompletedAt() != null || r.getActualReturnedAt() != null) continue;
            if (r.getMoveInCompletedAt() == null && r.getSetupCompletionDeadlineAt() != null
                && List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())) {
                if (!now.isBefore(r.getSetupCompletionDeadlineAt())) recovery(r, now, "Quá hạn hoàn tất setup");
                else if (!now.plus(24, ChronoUnit.HOURS).isBefore(r.getSetupCompletionDeadlineAt()))
                    notify(r, "Sắp hết hạn setup", "Vui lòng báo setup xong hoặc dời hạn trong số lượt cho phép.");
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
            if (r.getMoveInCompletedAt() != null && r.isInventoryPending()
                && r.getInventoryArrivedAt() == null && r.getInventoryResultPublishedAt() == null
                && r.getInventoryRemovalPublishedAt() == null && r.getInventoryEscalatedAt() == null
                && r.getInventoryFinalDeadlineAt() != null && !now.isBefore(r.getInventoryFinalDeadlineAt())
                && List.of(RentalStatus.setup, RentalStatus.active).contains(r.getStatus())) {
                recovery(r, now, "Quá hạn đến kiểm kê");
            }
            if (r.getInventoryRemovalDeadlineAt() != null && r.getInventoryEscalatedAt() == null
                && !now.isBefore(r.getInventoryRemovalDeadlineAt())
                && r.getStatus() != RentalStatus.return_inspection && r.getStatus() != RentalStatus.closing)
                recovery(r, now, "Quá hạn trả hàng do kiểm kê sai lệch");
        }
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
            if (!rc.isSetupExitRequested() || rc.isCustomerConfirmed()) continue;
            Instant published = rc.getReviewedAt() != null ? rc.getReviewedAt() : rc.getInspectedAt();
            if (published == null || now.isBefore(published.plus(48, ChronoUnit.HOURS))) continue;
            String title = now.isBefore(published.plus(72, ChronoUnit.HOURS)) ? "Nhắc xác nhận quyết toán" : "Quá hạn xác nhận quyết toán";
            String content = "Vui lòng xem bảng kê và xác nhận hoặc gửi ý kiến. Hệ thống không tự đồng ý thay bạn.";
            notifications.createNotificationOnce(rc.getCustomer().getId(), NotificationType.RETURN, title, content, rc.getId());
            notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN, title, content, rc.getId());
        }
        for (ReturnCase rc : returns.findByStatusIn(List.of(ReturnCaseStatus.requested, ReturnCaseStatus.scheduled))) {
            if (rc.getReadyForInspectionAt() == null) continue;
            var due = com.storagehub.service.calendar.ServiceCalendarPolicy.addWorkingHours(rc.getReadyForInspectionAt(), 4);
            if (!now.isBefore(due.minus(2, ChronoUnit.HOURS)))
                notifications.createFacilityOperationsNotificationsOnce(rc.getFacility().getId(), NotificationType.RETURN,
                    now.isBefore(due) ? "Sắp đến hạn nghiệm thu" : "Đã quá hạn nghiệm thu",
                    "Khách đã bàn giao đủ điều kiện. Vui lòng kiểm tra và hoàn tất biên bản nghiệm thu.", rc.getId());
        }
        for (MaintenanceTask t : tasks.findAll()) {
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
    private void notify(Rental r, String title, String content) {
        notifications.createNotificationOnce(r.getCustomer().getId(), NotificationType.CHECKIN, title, content, r.getReservation().getId());
        notifications.createFacilityOperationsNotificationsOnce(r.getFacility().getId(), NotificationType.CHECKIN, title, content, r.getReservation().getId());
    }
    private void recovery(Rental r, Instant now, String reason) {
        String title = "Thu hồi · " + reason;
        MaintenanceTaskType taskType = MaintenanceTaskType.maintenance;
        boolean existing = tasks.findByRental_IdAndTaskTypeAndStatusIn(r.getId(), taskType,
            List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)).stream().anyMatch(t -> title.equals(t.getTitle()));
        if (existing) return;
        MaintenanceTask t = new MaintenanceTask();
        t.setRental(r); t.setFacility(r.getFacility()); t.setStorageUnit(r.getStorageUnit());
        t.setTitle(title); t.setReason(reason + ". Không tự di chuyển hàng hoặc ghi nhận kho trống.");
        t.setRecoveryType(r.getMoveInCompletedAt() == null
            ? MaintenanceRecoveryType.SETUP_OVERDUE : r.getInventoryRemovalPublishedAt() == null
                ? MaintenanceRecoveryType.INVENTORY_OVERDUE : MaintenanceRecoveryType.MISMATCH_REMOVAL);
        t.setTaskType(taskType); t.setStatus(MaintenanceTaskStatus.open); t.setPriority(TaskPriority.high);
        t.setDeadlineAt(now.plus(24, ChronoUnit.HOURS)); t.setDueAt(now.atZone(InventoryTimelinePolicy.ZONE).toLocalDate());
        tasks.saveAndFlush(t);
        r.setInventoryEscalatedAt(now); rentals.save(r);
        notify(r, reason, "Đã tạo nhiệm vụ xử lý. Hàng chưa được tự động di chuyển. Vui lòng liên hệ cơ sở.");
    }
}
