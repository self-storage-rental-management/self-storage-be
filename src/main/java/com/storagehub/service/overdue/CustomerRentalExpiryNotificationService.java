package com.storagehub.service.overdue;

import com.storagehub.time.BusinessTime;

import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.service.NotificationService;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends a persisted customer notification once a rental period has actually ended. */
@Service
@RequiredArgsConstructor
public class CustomerRentalExpiryNotificationService {
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private final RentalRepository rentals;
    private final RentalPeriodResolver periods;
    private final NotificationService notifications;
    private final ObjectProvider<Clock> clocks;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.RentalReadSources.FinancialSource> financialSources;
    @org.springframework.beans.factory.annotation.Autowired
    private com.storagehub.service.email.CustomerLifecycleEmailService customerEmails;

    @Scheduled(fixedDelayString = "${storagehub.customer-expiry-notification.delay-ms:3600000}",
        initialDelayString = "${storagehub.customer-expiry-notification.initial-delay-ms:60000}")
    @Transactional
    public void notifyExpiredRentalCustomers() {
        var clock = clocks.getIfAvailable();
        var today = (clock == null ? Clock.systemUTC() : clock).instant().atZone(ZONE).toLocalDate();
        for (var id : rentals.findActiveUnreturnedIds(RentalStatus.active)) {
            var rental = rentals.findByIdForUpdate(id).orElse(null);
            if (rental == null || rental.getCustomer() == null || rental.getStatus() != RentalStatus.active
                || rental.getActualReturnedAt() != null) continue;
            var financial = financialSources.orderedStream().map(source -> source.read(rental)).flatMap(java.util.Optional::stream).findFirst();
            if (financial.isPresent() && financial.get().outstandingAmount() != null && financial.get().outstandingAmount().signum() > 0) {
                var bill = financial.get();
                if (bill.nextDueDate() != null) {
                    long dueIn = ChronoUnit.DAYS.between(today, bill.nextDueDate());
                    if (java.util.Set.of(7L, 3L, 1L).contains(dueIn)) customerEmails.billingReminder(rental, bill, dueIn);
                }
                if (bill.overdueAmount() != null && bill.overdueAmount().signum() > 0) customerEmails.billingReminder(rental, bill, -1);
            }
            var period = periods.read(rental).orElse(null);
            if (period == null) continue;
            String endDate = period.lastPermittedDate().toString();
            long remainingDays = ChronoUnit.DAYS.between(today, period.lastPermittedDate());
            if (java.util.Set.of(7L, 3L, 1L).contains(remainingDays)) {
                String content = "Hợp đồng thuê " + rental.getId() + " sẽ hết hạn ngày " + endDate
                    + " (còn " + remainingDays + " ngày). Vui lòng gửi yêu cầu gia hạn hoặc trả kho đúng hạn; yêu cầu gia hạn chưa được xác nhận không tự kéo dài hợp đồng.";
                notifications.notifyCustomerRentalEventOnce(rental.getCustomer().getId(), rental.getId(),
                    "Hợp đồng thuê sắp hết hạn", content);
            } else if (remainingDays < 0) {
                String content = "Hợp đồng thuê " + rental.getId() + " đã hết hạn ngày " + endDate
                    + ". Vui lòng liên hệ cơ sở để được hỗ trợ.";
                notifications.notifyCustomerRentalEventOnce(rental.getCustomer().getId(), rental.getId(),
                    "Hợp đồng thuê đã hết hạn", content);
            }
        }
    }
}
