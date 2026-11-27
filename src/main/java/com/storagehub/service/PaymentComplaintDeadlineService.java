package com.storagehub.service;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.PaymentComplaint;
import com.storagehub.domain.model.PaymentComplaintStatus;
import com.storagehub.domain.repo.PaymentComplaintRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentComplaintDeadlineService {
    private static final Duration FIRST_REMINDER_AFTER = Duration.ofHours(12);
    private static final Duration RECURRING_INTERVAL = Duration.ofHours(2);
    private static final Duration BEFORE_30M = Duration.ofMinutes(30);
    private static final Duration BEFORE_15M = Duration.ofMinutes(15);

    private final PaymentComplaintRepository complaintRepository;
    private final AuditLogService auditLogService;
    @Autowired(required = false)
    private NotificationService notificationService;

    @Transactional
    public int markOverdueComplaints() {
        var due = complaintRepository
            .findTop100ByStatusAndReviewDueAtLessThanEqualOrderByReviewDueAtAsc(
                PaymentComplaintStatus.PENDING, Instant.now()
            );
        for (PaymentComplaint complaint : due) {
            complaint.setStatus(PaymentComplaintStatus.REVIEW_OVERDUE);
            auditLogService.recordMutation(
                null, "PAYMENT_COMPLAINT_OVERDUE", "PaymentComplaint", complaint.getId(),
                complaint.getReservation().getFacility().getId(),
                Map.of("status", PaymentComplaintStatus.PENDING),
                Map.of("status", PaymentComplaintStatus.REVIEW_OVERDUE)
            );
        }
        if (!due.isEmpty()) complaintRepository.saveAll(due);
        return due.size();
    }

    @Transactional
    public int sendDueReminders() {
        return sendDueReminders(Instant.now());
    }

    @Transactional
    public int sendDueReminders(Instant now) {
        var pending = complaintRepository
            .findTop100ByStatusAndReviewDueAtAfterOrderByReviewDueAtAsc(
                PaymentComplaintStatus.PENDING, now
            );
        int sent = 0;
        for (PaymentComplaint complaint : pending) {
            if (complaint.getSubmittedAt() == null || complaint.getReviewDueAt() == null) continue;
            Duration elapsed = Duration.between(complaint.getSubmittedAt(), now);
            Duration remaining = Duration.between(now, complaint.getReviewDueAt());
            String stage = complaint.getLastReminderStage();
            String nextStage = null;
            String title = null;
            String content = null;
            String code = complaint.getReservation().getReservationCode();

            if (remaining.compareTo(BEFORE_15M) <= 0) {
                if (!"BEFORE_15M".equals(stage)) {
                    nextStage = "BEFORE_15M";
                    title = "Khẩn: Khiếu nại sắp hết hạn sau 15 phút";
                    content = "Khiếu nại thanh toán của đơn " + code + " chỉ còn dưới 15 phút trước khi quá hạn xử lý. Vui lòng kiểm tra và giải quyết ngay.";
                }
            } else if (remaining.compareTo(BEFORE_30M) <= 0) {
                if (!"BEFORE_30M".equals(stage) && !"BEFORE_15M".equals(stage)) {
                    nextStage = "BEFORE_30M";
                    title = "Khiếu nại sắp hết hạn sau 30 phút";
                    content = "Khiếu nại thanh toán của đơn " + code + " còn dưới 30 phút trước hạn xử lý. Vui lòng kiểm tra và xử lý.";
                }
            } else if (elapsed.compareTo(FIRST_REMINDER_AFTER) >= 0) {
                if (complaint.getLastReminderAt() == null) {
                    nextStage = "AFTER_12H";
                    title = "Đã qua 12 giờ chưa xử lý khiếu nại";
                    content = "Khiếu nại thanh toán của đơn " + code + " đã chờ hơn 12 giờ chưa được xử lý. Vui lòng đối soát.";
                } else if (!complaint.getLastReminderAt().plus(RECURRING_INTERVAL).isAfter(now)) {
                    nextStage = "INTERVAL_2H";
                    title = "Nhắc xử lý khiếu nại thanh toán";
                    content = "Khiếu nại thanh toán của đơn " + code + " vẫn đang chờ xử lý (nhắc định kỳ mỗi 2 giờ).";
                }
            }

            if (nextStage != null) {
                complaint.setLastReminderAt(now);
                complaint.setLastReminderStage(nextStage);
                if (notificationService != null) {
                    notificationService.createFacilityOperationsNotifications(
                        complaint.getReservation().getFacility().getId(),
                        NotificationType.PAYMENT,
                        title,
                        content,
                        complaint.getReservation().getId()
                    );
                }
                sent++;
            }
        }
        if (sent > 0) complaintRepository.saveAll(pending);
        return sent;
    }
}
