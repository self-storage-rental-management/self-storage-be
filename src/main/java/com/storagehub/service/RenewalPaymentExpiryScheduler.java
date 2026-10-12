package com.storagehub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled",havingValue="true")
public class RenewalPaymentExpiryScheduler {
    private final RenewalPaymentExpiryService expiry;

    @Scheduled(fixedDelayString="${storagehub.renewal.payment-expiry-check-ms:15000}",
        initialDelayString="${storagehub.renewal.payment-expiry-initial-delay-ms:15000}")
    public void reconcile() { expiry.reconcileExpiredIntents(); }
}
