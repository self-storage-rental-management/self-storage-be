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

/** Advances the paid term at the free-period deadline unless the customer has already confirmed an earlier start. */
@Service
@RequiredArgsConstructor
public class RentalSetupLifecycleService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final RentalRepository rentalRepository;
    private final NotificationService notificationService;

    @Autowired(required = false)
    private MaintenanceTaskRepository maintenanceTaskRepository;

    @Value("${storagehub.inventory.recovery-sla-hours:4}")
    private long inventoryRecoverySlaHours = 4;

    static boolean customerCanStartAfterInventory(Rental rental, Instant now) {
        return rental.getStatus() == RentalStatus.setup
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
                        : " Kiểm kê đã hoàn tất. Bạn có thể xác nhận bắt đầu kỳ thuê sớm trong Hồ sơ thuê."));
                rental.setSetupReminderTwoDaysAt(now);
            }
            if (daysUntilPaidStart <= 1 && daysUntilPaidStart > 0
                && rental.getSetupReminderLastDayAt() == null) {
                notifyCustomer(rental, "Hôm nay là ngày chuẩn bị miễn phí cuối cùng",
                    timeline(rental) + " Kỳ thuê tự động bắt đầu theo lịch nếu bạn chưa xác nhận bắt đầu sớm.");
                rental.setSetupReminderLastDayAt(now);
            }
            if (!rental.getSetupEndsAt().isAfter(now)) {
                rental.setStatus(RentalStatus.active);
            }
            rentalRepository.save(rental);
        }

        for (Rental rental : rentalRepository.findByInventoryPendingTrueAndStatusIn(
            java.util.List.of(RentalStatus.setup, RentalStatus.active))) {
            if (rental.getInventoryDueAt() != null && !rental.getInventoryDueAt().isAfter(now)
                && rental.getInventoryDeadlineReminderAt() == null) {
                remindInventoryDeadline(rental, now);
                rentalRepository.save(rental);
            }
            if (rental.getInventoryFinalDeadlineAt() != null
                && !rental.getInventoryFinalDeadlineAt().isAfter(now)
                && rental.getInventoryEscalatedAt() == null) {
                escalateInventory(rental, now, "Đã quá hạn kiểm kê hàng hóa");
                rentalRepository.save(rental);
            }
        }
    }

    private void remindInventoryDeadline(Rental rental, Instant now) {
        notifyCustomer(rental, "Sắp đến hạn kiểm kê hàng hóa",
            "Hồ sơ hàng hóa của đơn " + rental.getReservation().getReservationCode()
                + " sắp đến hạn kiểm kê. Staff đã nhận nhiệm vụ và sẽ cập nhật kết quả trên hồ sơ.");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN,
            "Sắp đến hạn kiểm kê hàng hóa",
            "Đơn " + rental.getReservation().getReservationCode()
                + " sắp đến hạn kiểm kê. Vui lòng ưu tiên xử lý nhiệm vụ kiểm kê.",
            rental.getReservation().getId()
        );
        rental.setInventoryDeadlineReminderAt(now);
    }

    private void escalateInventory(Rental rental, Instant now, String title) {
        createInventoryRecoveryTask(rental, now);
        notifyCustomer(rental, title,
            "Rental vẫn ACTIVE từ " + rental.getStartDate()
                + ", nhưng kiểm kê đầu vào đang chờ xử lý. Vui lòng phối hợp với nhân viên cơ sở.");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN, title,
            "Đơn " + rental.getReservation().getReservationCode()
                + " đã quá hạn kiểm kê hàng hóa. Vui lòng mở nhiệm vụ kiểm kê và xử lý theo quy trình, không tự động hoàn tất hồ sơ.",
            rental.getReservation().getId()
        );
        rental.setInventoryEscalatedAt(now);
    }

    private void createInventoryRecoveryTask(Rental rental, Instant now) {
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
        task.setTitle("Thu hồi hàng do quá hạn kiểm kê");
        task.setTaskType(MaintenanceTaskType.maintenance);
        task.setRecoveryType(MaintenanceRecoveryType.INVENTORY_OVERDUE);
        task.setReason(
            "Hồ sơ đã quá hạn cuối kiểm kê hàng hóa. Không tự động di chuyển hoặc loại bỏ hàng. "
                + "Cần hai nhân viên kiểm kê, lập bằng chứng, đưa hàng ra khu vực lưu tạm và ghi nhận bàn giao."
        );
        task.setPriority(TaskPriority.high);
        task.setStatus(MaintenanceTaskStatus.open);
        task.setDueAt(now.atZone(VIETNAM_ZONE).toLocalDate());
        task.setDeadlineAt(now.plus(Math.max(1, inventoryRecoverySlaHours), java.time.temporal.ChronoUnit.HOURS));
        maintenanceTaskRepository.saveAndFlush(task);
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(),
            NotificationType.CHECKIN,
            "Cần thu hồi hàng quá hạn kiểm kê",
            "Đơn " + rental.getReservation().getReservationCode()
                + " đã quá hạn cuối kiểm kê. Cần phân công hai nhân viên xử lý, ghi bằng chứng và bàn giao hàng vào khu vực lưu tạm.",
            rental.getReservation().getId()
        );
    }

    private void notifyCustomer(Rental rental, String title, String content) {
        notificationService.createNotification(
            rental.getCustomer().getId(), NotificationType.CHECKIN, title, content, rental.getId()
        );
    }

    private String timeline(Rental rental) {
        LocalDate paidStart = rental.getStartDate();
        return "Thời gian chuẩn bị đến hết " + paidStart.minusDays(1) + ", thời hạn thuê tính từ " + paidStart + ".";
    }
}
