package com.storagehub.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled",havingValue="true")
public class RenewalPaymentExpiryScheduler {
    private final RenewalPaymentExpiryService expiry;
    private final org.springframework.beans.factory.ObjectProvider<com.storagehub.service.ledger.RenewalPaymentReconciler> reconciler;

    @Scheduled(fixedDelayString="${storagehub.renewal.payment-expiry-check-ms:15000}",
        initialDelayString="${storagehub.renewal.payment-expiry-initial-delay-ms:15000}")
    public void reconcile() {
        try { expiry.reconcileExpiredIntents(); }
        finally { var source=reconciler.getIfAvailable();if(source!=null)source.retryPending(); }
    }
}
