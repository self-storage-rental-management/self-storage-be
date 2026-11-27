package com.storagehub.service.renewal.operations;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Each record expires independently; an unresolved payment cannot block the other records. */
@Component @RequiredArgsConstructor @Slf4j
public class RenewalSigningExpiryCoordinator {
    private final EntityManager em;
    private final RenewalOperationService operations;
    private final PlatformTransactionManager transactions;

    @Scheduled(initialDelay=30000,fixedDelay=60000)
    public void reconcile() {
        var tx=new TransactionTemplate(transactions);
        List<UUID> ids=tx.execute(status -> em.createQuery(
            "select s.id from RenewalOperationState s where s.phase=:phase and s.effectiveSigningDeadline<:now order by s.effectiveSigningDeadline",UUID.class)
            .setParameter("phase",RenewalOperationState.Phase.SIGNING)
            .setParameter("now",Instant.now()).setMaxResults(100).getResultList());
        if(ids==null)return;
        for(UUID id:ids) {
            try { operations.expire(id); }
            catch(RuntimeException failure) { log.warn("Renewal signing expiry deferred for {}: {}",id,failure.getMessage()); }
        }
    }
}
