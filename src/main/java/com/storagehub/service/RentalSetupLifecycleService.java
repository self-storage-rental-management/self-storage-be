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

    /**
     * Customers may confirm the paid period has effectively started early once setup and
     * inventory are genuinely finished. The paid start date itself is never moved earlier;
     * this only records the confirmed start of billing that was already agreed.
     */
    static boolean customerCanStartAfterInventory(Rental rental, Instant now) {
        return rental.getSetupCompletionDeadlineAt() == null && rental.getStatus() == RentalStatus.setup
            && rental.getReservation() != null
            && rental.getReservation().getStatus() == ReservationStatus.COMPLETED
            && rental.getSetupEndsAt() != null && now.isBefore(rental.getSetupEndsAt())
            && rental.getActualReturnedAt() == null && rental.getCompletedAt() == null
            && rental.getMoveInCompletedAt() != null && rental.getInventoryVerifiedAt() != null
            && !rental.isInventoryPending()
            && !rental.getMoveInCompletedAt().isAfter(rental.getInventoryVerifiedAt());
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
            if (daysUntilPaidStart <= 2 && daysUntilPaidStart > 0
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
            if (!rental.getSetupEndsAt().isAfter(now)) {
                rental.setStatus(RentalStatus.active);
            }
            rentalRepository.save(rental);
        }

        for (Rental rental : rentalRepository.findByInventoryPendingTrueAndStatusIn(
            java.util.List.of(RentalStatus.setup, RentalStatus.active))) {
            // A published removal window replaces the inventory attendance clock.
            if (rental.getInventoryRemovalPublishedAt() != null) continue;
            if (rental.getInventoryArrivedAt() != null || rental.getInventoryResultPublishedAt() != null) continue;
            if (rental.getInventoryEscalatedAt() != null) continue;
            Instant removalDeadline = rental.getInventoryRemovalDeadlineAt();
            if (removalDeadline != null) {
                if (!now.isBefore(removalDeadline)) {
                    escalateInventory(rental, now, true);
                    rentalRepository.save(rental);
                }
                continue;
            }
            if (rental.getInventoryFinalDeadlineAt() != null
                && !now.isBefore(rental.getInventoryFinalDeadlineAt())) {
                escalateInventory(rental, now, false);
                rentalRepository.save(rental);
                continue;
            }
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

    /**
     * Closes the "customer did not arrive for inventory" recovery task once the case has
     * moved to the mismatch-return branch. The two clocks must not run at the same time:
     * the return window owns the case from this point on.
     */
    @Transactional
    public int supersedeInventoryAttendanceTasks(Rental rental, String reason) {
        if (maintenanceTaskRepository == null || rental == null || rental.getId() == null) return 0;
        int closed = 0;
        for (MaintenanceTask task : maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(
            rental.getId(),
            MaintenanceTaskType.maintenance,
            java.util.List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress))) {
            if (task.getRecoveryType() != MaintenanceRecoveryType.INVENTORY_OVERDUE) continue;
            task.setStatus(MaintenanceTaskStatus.cancelled);
            task.setResultReport((task.getResultReport() == null ? "" : task.getResultReport() + " | ")
                + "Đã dừng theo dõi quá hạn kiểm kê: " + reason);
            maintenanceTaskRepository.save(task);
            closed++;
        }
        if (closed > 0) maintenanceTaskRepository.flush();
        return closed;
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
