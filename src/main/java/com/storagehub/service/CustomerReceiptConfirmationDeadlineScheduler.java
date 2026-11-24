package com.storagehub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerReceiptConfirmationDeadlineScheduler {

    private final CustomerReceiptConfirmationDeadlineService deadlineService;

    @Scheduled(
        fixedDelayString = "${app.checkin.receipt-confirmation-check-ms:60000}",
        initialDelayString = "${app.checkin.receipt-confirmation-initial-delay-ms:10000}"
    )
    public void notifyDueConfirmations() {
        deadlineService.notifyDueConfirmations();
    }
}
