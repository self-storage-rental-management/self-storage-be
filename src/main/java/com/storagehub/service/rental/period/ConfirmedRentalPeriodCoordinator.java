package com.storagehub.service.rental.period;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.rental-period.enabled",havingValue="true")
public class ConfirmedRentalPeriodCoordinator {
    private final ConfirmedRentalPeriodImport importer;
    private final AtomicBoolean running=new AtomicBoolean();
    private UUID cursor;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ConfirmedRentalPeriodCoordinator.class);
    @Scheduled(initialDelayString="${storagehub.rental.confirmed-period.initial-delay-ms:1000}",fixedDelayString="${storagehub.rental.confirmed-period.delay-ms:60000}")
    public void reconcile() {
        if(!running.compareAndSet(false,true))return;
        try {
            var ids=importer.candidates(cursor);
            if(ids.isEmpty()){cursor=null;return;}
            for(var id:ids) {
                try{importer.confirm(id);}catch(RuntimeException failure){LOG.warn("Confirmed rental period import deferred for {} ({})",id,failure.getClass().getSimpleName());}
                cursor=id;
            }
        } finally {running.set(false);}
    }
}
