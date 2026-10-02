package com.storagehub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentComplaintDeadlineScheduler {
    private final PaymentComplaintDeadlineService deadlineService;

    @Scheduled(
        fixedDelayString = "${app.reservation.expiration-check-ms:60000}",
        initialDelayString = "${app.reservation.expiration-initial-delay-ms:60000}"
    )
    public void markOverdue() {
        deadlineService.markOverdueComplaints();
    }
}
