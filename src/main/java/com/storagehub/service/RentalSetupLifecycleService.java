package com.storagehub.service;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.repo.RentalRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Advances the paid term at the free-period deadline unless the customer has already confirmed an earlier start. */
@Service
@RequiredArgsConstructor
public class RentalSetupLifecycleService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final RentalRepository rentalRepository;
    private final NotificationService notificationService;

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

        for (Rental rental : rentalRepository.findByStatusAndInventoryPendingTrue(RentalStatus.active)) {
            if (rental.getInventoryDueAt() != null && !rental.getInventoryDueAt().isAfter(now)
                && rental.getInventoryEscalatedAt() == null) {
                escalateInventory(rental, now, "Khách chưa báo hoàn tất chuẩn bị hàng");
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
            "Đơn " + rental.getReservation().getReservationCode()
                + " chưa có xác nhận hoàn tất chuẩn bị hàng. Chưa tạo nhiệm vụ kiểm kê tự động.",
            rental.getReservation().getId()
        );
        rental.setInventoryEscalatedAt(now);
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
