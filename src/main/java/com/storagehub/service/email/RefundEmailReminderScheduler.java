package com.storagehub.service.email;

import com.storagehub.domain.model.NotificationType;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.repo.NotificationRepository;
import com.storagehub.service.NotificationService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RefundEmailReminderScheduler {
    private final EntityManager em;
    private final NotificationRepository records;
    private final NotificationService notifications;

    @Scheduled(fixedDelayString = "${app.refund.reminder-delay-ms:3600000}")
    @Transactional
    public void remindOverdueRefunds() {
        var due = em.createQuery("select r from ReturnCase r where r.status=:status and r.refundExpectedAt<:now", ReturnCase.class)
            .setParameter("status", ReturnCaseStatus.refund_pending).setParameter("now", Instant.now()).setMaxResults(100).getResultList();
        for (ReturnCase returned : due) {
            String title = "Hoàn tiền đang chậm so với dự kiến";
            String content = "Hồ sơ trả kho đã quá hạn xử lý hoàn tiền dự kiến. Hệ thống đã gửi nhắc cho quản lý cơ sở; bạn có thể liên hệ hỗ trợ và xem chứng từ trong hồ sơ.";
            if (records.existsByUser_IdAndRelatedEntityIdAndTypeAndTitleAndContent(returned.getCustomer().getId(), returned.getId(), NotificationType.RETURN, title, content)) continue;
            notifications.createFacilityOperationsNotifications(returned.getFacility().getId(), NotificationType.RETURN,
                "Cần xử lý hoàn tiền quá hạn", "Hồ sơ " + returned.getId() + " đã quá hạn hoàn tiền dự kiến, cần cập nhật lý do và tiến độ cho khách.", returned.getId());
            notifications.createNotification(returned.getCustomer().getId(), NotificationType.RETURN, title, content, returned.getId());
        }
    }
}
