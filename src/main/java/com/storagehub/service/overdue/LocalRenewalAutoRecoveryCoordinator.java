package com.storagehub.service.overdue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.renewal-auto-recovery.enabled",
    "storagehub.integration.recovery-tasks.enabled"},havingValue="true")
public class LocalRenewalAutoRecoveryCoordinator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(LocalRenewalAutoRecoveryCoordinator.class);
    private final LocalRenewalAutoRecoveryService service;
    @Scheduled(initialDelayString="${storagehub.renewal.auto-recovery.initial-delay-ms:60000}",
        fixedDelayString="${storagehub.renewal.auto-recovery.delay-ms:60000}")
    public void reconcile() {
        for(var id:service.candidates())try{service.handoff(id);}
        catch(RuntimeException deferred){LOG.warn("Rental {} auto Recovery deferred: {}",id,deferred.getMessage());}
    }
}
