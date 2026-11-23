package com.storagehub.service;

import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.repo.CheckInRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerReceiptConfirmationDeadlineService {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter
        .ofPattern("dd/MM/yyyy HH:mm")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final CheckInRepository checkInRepository;
    private final NotificationService notificationService;

    @Transactional
    public int notifyDueConfirmations() {
        Instant now = Instant.now();
        int notified = 0;
        for (CheckIn checkIn : checkInRepository
            .findTop100ByStatusAndCheckedInAtIsNotNullOrderByCheckedInAtAsc(CheckInStatus.handover_pending_receipt)) {
            Instant handoverAt = checkIn.getCheckedInAt();
            Instant dueAt = ReservationCheckInPolicy.customerReceiptConfirmationDueAt(handoverAt);
            Instant reminderAt = ReservationCheckInPolicy.customerReceiptConfirmationReminderAt(handoverAt);
            Instant releaseAt = ReservationCheckInPolicy.customerReceiptReleaseAt(handoverAt);
            if (dueAt == null || reminderAt == null || releaseAt == null) continue;

            var reservation = checkIn.getReservation();
            String dueText = DISPLAY_FORMAT.format(dueAt);
            String releaseText = DISPLAY_FORMAT.format(releaseAt);
            if (!now.isBefore(releaseAt)) {
                notificationService.createNotificationOnce(
                    reservation.getCustomer().getId(), NotificationType.CHECKIN,
                    "Đã hết 3 ngày chờ xác nhận nhận kho",
                    "Đơn " + reservation.getReservationCode()
                        + " đã quá thời hạn 3 ngày để xác nhận nhận kho. Manager có thể giải phóng gian kho nếu bạn chưa xác nhận.",
                    reservation.getId()
                );
                notificationService.createFacilityOperationsNotificationsOnce(
                    reservation.getFacility().getId(), NotificationType.CHECKIN,
                    "Đơn đủ điều kiện giải phóng gian kho",
                    "Đơn " + reservation.getReservationCode()
                        + " đã quá thời hạn xác nhận nhận kho từ " + releaseText
                        + ". Chỉ Manager có thể giải phóng gian kho.",
                    reservation.getId()
                );
                notified++;
            } else if (!now.isBefore(reminderAt) && now.isBefore(dueAt)) {
                notificationService.createNotificationOnce(
                    reservation.getCustomer().getId(), NotificationType.CHECKIN,
                    "Sắp hết hạn xác nhận nhận kho",
                    "Đơn " + reservation.getReservationCode()
                        + " còn thời gian đến " + dueText
                        + " để xác nhận đã nhận kho. Thời gian setup chưa bắt đầu.",
                    reservation.getId()
                );
                notificationService.createFacilityOperationsNotificationsOnce(
                    reservation.getFacility().getId(), NotificationType.CHECKIN,
                    "Sắp hết hạn chờ khách xác nhận nhận kho",
                    "Đơn " + reservation.getReservationCode()
                        + " còn thời gian đến " + dueText
                        + " để khách xác nhận nhận kho.",
                    reservation.getId()
                );
                notified++;
            } else if (!now.isBefore(dueAt)) {
                notificationService.createNotificationOnce(
                    reservation.getCustomer().getId(), NotificationType.CHECKIN,
                    "Đã quá hạn xác nhận nhận kho",
                    "Đơn " + reservation.getReservationCode()
                        + " đã quá hạn xác nhận nhận kho từ " + dueText
                        + ". Thời gian setup chưa bắt đầu. Vui lòng liên hệ cơ sở để được hỗ trợ.",
                    reservation.getId()
                );
                notificationService.createFacilityOperationsNotificationsOnce(
                    reservation.getFacility().getId(), NotificationType.CHECKIN,
                    "Đơn chờ khách xác nhận nhận kho đã quá hạn",
                    "Đơn " + reservation.getReservationCode()
                        + " đã quá hạn xác nhận nhận kho từ " + dueText
                        + ". Vui lòng liên hệ khách hàng và xử lý hồ sơ tiếp theo.",
                    reservation.getId()
                );
                notified++;
            }
        }
        return notified;
    }
}
