package com.storagehub.service.support;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.SupportTicketStatus;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in bounded scan. Shared close policy remains authoritative inside each locked command. */
@Component
@ConditionalOnProperty(prefix="app.support.auto-close",name="enabled",havingValue="true")
public class SupportAutoCloseCoordinator {
    private static final Logger log=LoggerFactory.getLogger(SupportAutoCloseCoordinator.class);
    private final EntityManager em;
    private final SupportService service;
    private final TransactionTemplate reads;
    private final int batchSize;
    private final AtomicBoolean running=new AtomicBoolean();
    private UUID cursor;

    public SupportAutoCloseCoordinator(EntityManager em,PlatformTransactionManager transactions,SupportService service,
        @Value("${app.support.auto-close.batch-size:100}") int batchSize){
        if(batchSize<1||batchSize>500)throw new IllegalArgumentException("Support auto-close batch size must be 1..500");
        this.em=em;this.service=service;this.batchSize=batchSize;
        reads=new TransactionTemplate(transactions);reads.setReadOnly(true);
    }

    @Scheduled(initialDelayString="${app.support.auto-close.initial-delay-ms:60000}",fixedDelayString="${app.support.auto-close.fixed-delay-ms:60000}")
    public void scan(){
        if(!running.compareAndSet(false,true))return;
        try{
            List<UUID> ids=reads.execute(tx->{
                var query=em.createQuery("select s.id from SupportState s join s.ticket t where t.facility is not null and t.status=:status"+(cursor==null?"":" and s.id>:cursor")+" order by s.id",UUID.class)
                    .setParameter("status",SupportTicketStatus.resolved).setMaxResults(batchSize);
                if(cursor!=null)query.setParameter("cursor",cursor);
                return query.getResultList();
            });
            if(ids==null||ids.isEmpty()){cursor=null;return;}
            int deferred=0;
            for(UUID id:ids){
                try{service.autoClose(id);} // Separate transaction per ticket; one missing source never rolls back a batch.
                catch(ApiException e){deferred++;}
                catch(RuntimeException e){deferred++;log.warn("Support auto-close command failed; ticket remains subject to locked retry ({})",e.getClass().getSimpleName());}
                cursor=id;
            }
            if(deferred>0)log.warn("Support auto-close deferred {} commands; inspect shared policy/result sources before enabling rollout",deferred);
        }finally{running.set(false);}
    }
}
