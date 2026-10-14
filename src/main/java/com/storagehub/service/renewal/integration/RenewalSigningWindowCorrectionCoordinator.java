package com.storagehub.service.renewal.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name="storagehub.integration.renewal-signing-window-correction.enabled",havingValue="true")
public class RenewalSigningWindowCorrectionCoordinator {
    private final RenewalSigningWindowCorrectionService service;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RenewalSigningWindowCorrectionCoordinator.class);
    @Scheduled(initialDelay=5000,fixedDelay=60000)
    public void correct() {
        for(var id:service.candidates())try{service.correct(id);}
        catch(RuntimeException unavailable){LOG.warn("Renewal {} signing-window correction deferred: {}",id,unavailable.getMessage());}
    }
}
