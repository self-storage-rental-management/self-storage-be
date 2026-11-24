package com.storagehub.service;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.MaintenanceRecoveryType;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.MaintenanceTaskStatus;
import com.storagehub.domain.model.MaintenanceTaskType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.TaskPriority;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.RentalRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Advances the paid term at the captured free-period deadline. */
@Service
@RequiredArgsConstructor
public class RentalSetupLifecycleService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final RentalRepository rentalRepository;
    private final NotificationService notificationService;

    @Autowired(required = false)
    private MaintenanceTaskRepository maintenanceTaskRepository;

    @Value("${storagehub.inventory.recovery-sla-hours:24}")
    private long inventoryRecoverySlaHours = 24;

    /** Kept for response compatibility; customers cannot move the paid start earlier. */
    static boolean customerCanStartAfterInventory(Rental rental, Instant now) {
        return false;
    }

    @Scheduled(
        fixedDelayString = "${storagehub.setup-lifecycle.delay-ms:60000}",
        initialDelayString = "${storagehub.setup-lifecycle.initial-delay-ms:60000}"
    )
    @Transactional
    public void advanceSetupRentals() {
        Instant now = Instant.now();
        LocalDate today = now.atZone(VIETNAM_ZONE).toLocalDate();
        for (Rental rental : rentalRepository.findByStatusAndSetupEndsAtIsNotNull(RentalStatus.setup)) {
            LocalDate paidStart = rental.getSetupEndsAt().atZone(VIETNAM_ZONE).toLocalDate();
            long daysUntilPaidStart = ChronoUnit.DAYS.between(today, paidStart);
            if (daysUntilPaidStart == 2
                && rental.getSetupReminderTwoDaysAt() == null) {
                notifyCustomer(rental,
                    daysUntilPaidStart == 2 ? "Còn 2 ngày chuẩn bị miễn phí" : "Nhắc thời gian chuẩn bị sắp kết thúc",
                    timeline(rental) + (rental.getInventoryVerifiedAt() == null
                        ? " Vui lòng hoàn tất chuyển hàng và chuẩn bị kiểm kê."
                        : " Kiểm kê đã hoàn tất. Kỳ thuê vẫn bắt đầu theo mốc sau thời gian chuẩn bị miễn phí đã chốt."));
                rental.setSetupReminderTwoDaysAt(now);
            }
            if (daysUntilPaidStart <= 1 && daysUntilPaidStart > 0
                && rental.getSetupReminderLastDayAt() == null) {
                notifyCustomer(rental, "Hôm nay là ngày chuẩn bị miễn phí cuối cùng",
                    timeline(rental) + " Kỳ thuê sẽ tự động bắt đầu theo lịch đã chốt.");
                rental.setSetupReminderLastDayAt(now);
            }
            if (rental.getStartDate() != null && !rental.getStartDate().isAfter(today)) {
                rental.setStatus(RentalStatus.active);
            }
            rentalRepository.save(rental);
        }

        for (Rental rental : rentalRepository.findByInventoryPendingTrueAndStatusIn(
            java.util.List.of(RentalStatus.setup, RentalStatus.active))) {
            // A published removal window replaces the inventory attendance clock.
            if (rental.getInventoryRemovalPublishedAt() != null) continue;
            if (rental.getInventoryArrivedAt() != null || rental.getInventoryResultPublishedAt() != null) continue;
            if (rental.getInventoryDueAt() != null && !rental.getInventoryDueAt().isAfter(now)
                && rental.getInventoryDeadlineReminderAt() == null) {
                remindInventoryDeadline(rental, now);
                rentalRepository.save(rental);
            }
        }
    }

    private void remindInventoryDeadline(Rental rental, Instant now) {
        notifyCustomer(rental, "Sắp đến hạn kiểm kê hàng hóa",
            "Hồ sơ hàng hóa của đơn " + rental.getReservation().getReservationCode()
                + " sắp đến hạn kiểm kê. Nếu chưa chọn lịch, vui lòng đặt lịch trong Hồ sơ thuê.");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN,
            "Sắp đến hạn kiểm kê hàng hóa",
            "Đơn " + rental.getReservation().getReservationCode()
                + " sắp đến hạn kiểm kê. Vui lòng ưu tiên xử lý nhiệm vụ kiểm kê.",
            rental.getReservation().getId()
        );
        rental.setInventoryDeadlineReminderAt(now);
    }

    private void escalateInventory(Rental rental, Instant now, boolean removalOverdue) {
        String title = removalOverdue ? "Đã hết hạn đưa hàng sai lệch ra khỏi kho" : "Đã quá hạn kiểm kê hàng hóa";
        createInventoryRecoveryTask(rental, now, title);
        notifyCustomer(rental, title,
            "Hồ sơ đã chuyển sang chờ thu hồi. Vui lòng phối hợp với nhân viên cơ sở để xử lý hàng.");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN, title,
            "Đơn " + rental.getReservation().getReservationCode()
                + " đã quá hạn xử lý. Manager cần phân công nhân viên thực hiện nhiệm vụ thu hồi.",
            rental.getReservation().getId()
        );
        rental.setInventoryEscalatedAt(now);
    }

    private void createInventoryRecoveryTask(Rental rental, Instant now, String reason) {
        if (maintenanceTaskRepository == null || rental.getId() == null || rental.getStorageUnit() == null) {
            return;
        }
        boolean alreadyOpen = maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
            rental.getId(),
            MaintenanceTaskType.maintenance,
            java.util.List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress)
        ).stream().anyMatch(task -> task.getRecoveryType() == MaintenanceRecoveryType.INVENTORY_OVERDUE);
        if (alreadyOpen) return;

        MaintenanceTask task = new MaintenanceTask();
        task.setFacility(rental.getFacility());
        task.setStorageUnit(rental.getStorageUnit());
        task.setRental(rental);
        task.setTitle("Thu hồi hàng · " + rental.getReservation().getReservationCode());
        task.setTaskType(MaintenanceTaskType.maintenance);
        task.setRecoveryType(MaintenanceRecoveryType.INVENTORY_OVERDUE);
        task.setReason(
            reason + ". Không tự động di chuyển hoặc loại bỏ hàng. "
                + "Cần hai nhân viên kiểm kê, lập bằng chứng, đưa hàng ra khu vực lưu tạm và ghi nhận bàn giao."
        );
        task.setPriority(TaskPriority.high);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(now.atZone(VIETNAM_ZONE).toLocalDate());
        task.setDeadlineAt(now.plus(Math.max(1, inventoryRecoverySlaHours), java.time.temporal.ChronoUnit.HOURS));
        maintenanceTaskRepository.saveAndFlush(task);
    }

    private void notifyCustomer(Rental rental, String title, String content) {
        notificationService.createNotification(
            rental.getCustomer().getId(), NotificationType.CHECKIN, title, content,
            rental.getReservation() == null ? rental.getId() : rental.getReservation().getId()
        );
    }

    private String timeline(Rental rental) {
        String setupEnd = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(VIETNAM_ZONE).format(rental.getSetupEndsAt());
        return "Thời gian chuẩn bị miễn phí đến " + setupEnd + " (giờ Việt Nam). Kỳ thuê tính từ " + rental.getStartDate() + ".";
    }
}
