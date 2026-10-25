package com.storagehub.service;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.repo.RentalRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Advances the paid term by time, never by Staff inventory completion. */
@Service
@RequiredArgsConstructor
public class RentalSetupLifecycleService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final RentalRepository rentalRepository;
    private final NotificationService notificationService;

    @Scheduled(
        fixedDelayString = "${storagehub.setup-lifecycle.delay-ms:3600000}",
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
                    daysUntilPaidStart == 2 ? "Còn 2 ngày setup miễn phí" : "Nhắc chuẩn bị kết thúc setup",
                    timeline(rental) + " Vui lòng hoàn tất chuyển hàng và chuẩn bị kiểm kê.");
                rental.setSetupReminderTwoDaysAt(now);
            }
            if (daysUntilPaidStart <= 1 && daysUntilPaidStart > 0
                && rental.getSetupReminderLastDayAt() == null) {
                notifyCustomer(rental, "Hôm nay là ngày setup miễn phí cuối cùng",
                    timeline(rental) + " Kỳ thuê vẫn tự động bắt đầu đúng hạn dù kiểm kê chưa hoàn tất.");
                rental.setSetupReminderLastDayAt(now);
            }
            if (!rental.getSetupEndsAt().isAfter(now)) {
                rental.setStatus(RentalStatus.active);
                if (rental.isInventoryPending() && rental.getInventoryTaskCreatedAt() == null) {
                    createInventoryTask(rental, now,
                        "Kỳ thuê đã bắt đầu nhưng hồ sơ chưa kiểm kê đầu vào");
                }
            }
            rentalRepository.save(rental);
        }

        for (Rental rental : rentalRepository.findByStatusAndInventoryPendingTrue(RentalStatus.active)) {
            if (rental.getInventoryDueAt() != null && !rental.getInventoryDueAt().isAfter(now)
                && rental.getInventoryEscalatedAt() == null) {
                escalateInventory(rental, now, "Đã đến hạn kiểm kê đầu vào");
                rentalRepository.save(rental);
            }
        }
    }

    private void escalateInventory(Rental rental, Instant now, String title) {
        notifyCustomer(rental, title,
            "Rental vẫn ACTIVE từ " + rental.getStartDate()
                + ", nhưng kiểm kê đầu vào đang chờ xử lý. Vui lòng phối hợp với nhân viên cơ sở.");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN, title,
            "Tạo nhiệm vụ kiểm kê cho đơn " + rental.getReservation().getReservationCode()
                + ". Hạn xử lý: " + rental.getInventoryDueAt() + ".",
            rental.getReservation().getId()
        );
        rental.setInventoryEscalatedAt(now);
    }

    private void createInventoryTask(Rental rental, Instant now, String title) {
        notifyCustomer(rental, title,
            "Rental đã ACTIVE từ " + rental.getStartDate()
                + ". Kiểm kê đầu vào có hạn đến " + rental.getInventoryDueAt() + ".");
        notificationService.createFacilityOperationsNotifications(
            rental.getFacility().getId(), NotificationType.CHECKIN, title,
            "Tạo nhiệm vụ kiểm kê cho đơn " + rental.getReservation().getReservationCode()
                + ". Hạn xử lý: " + rental.getInventoryDueAt() + ".",
            rental.getReservation().getId()
        );
        rental.setInventoryTaskCreatedAt(now);
    }

    private void notifyCustomer(Rental rental, String title, String content) {
        notificationService.createNotification(
            rental.getCustomer().getId(), NotificationType.CHECKIN, title, content, rental.getId()
        );
    }

    private String timeline(Rental rental) {
        LocalDate paidStart = rental.getStartDate();
        return "Setup đến hết " + paidStart.minusDays(1) + ", thời hạn thuê tính từ " + paidStart + ".";
    }
}
