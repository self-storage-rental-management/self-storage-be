package com.storagehub.service.rental;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storagehub.rental.lifecycle-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class RentalLifecycleScheduler {

    private final RentalContractLifecycleService lifecycleService;

    @Scheduled(
        fixedDelayString = "${storagehub.rental.lifecycle-check-ms:600000}",
        initialDelayString = "${storagehub.rental.lifecycle-initial-delay-ms:30000}"
    )
    public void runLifecycleJobs() {
        try {
            var expSummary = lifecycleService.processContractExpirations();
            var paymentSummary = lifecycleService.processLatePayments();
            log.info("Rental lifecycle jobs executed successfully. Expirations evaluated: {}, contracts expired: {}, pre-expiry notices: {}, late payment D-3: {}, D+1: {}, D+3 watch: {}, D+7 escalation: {}",
                expSummary.expirationsEvaluated(), expSummary.contractsMarkedExpired(), expSummary.preExpirationRemindersSent(),
                paymentSummary.latePaymentRemindersDMinus3(), paymentSummary.overdueNoticesDPlus1(),
                paymentSummary.gracePeriodEndedWatchListDPlus3(), paymentSummary.managerEscalationsDPlus7());
        } catch (Exception ex) {
            log.error("Error executing rental contract lifecycle scheduler job", ex);
        }
    }
}
