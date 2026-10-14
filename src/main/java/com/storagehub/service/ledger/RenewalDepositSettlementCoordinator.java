package com.storagehub.service.ledger;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
public class RenewalDepositSettlementCoordinator {
    private final RenewalDepositSettlementService deposits;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RenewalDepositSettlementCoordinator.class);
    @Scheduled(initialDelayString="${storagehub.renewal.deposit-settlement.initial-delay-ms:60000}",fixedDelayString="${storagehub.renewal.deposit-settlement.delay-ms:60000}")
    public void reconcile() {
        for(var id:deposits.candidates())try{deposits.reconcile(id);}
        catch(RuntimeException unavailable){LOG.warn("Renewal {} deposit settlement deferred: {}",id,unavailable.getMessage());}
    }
}
