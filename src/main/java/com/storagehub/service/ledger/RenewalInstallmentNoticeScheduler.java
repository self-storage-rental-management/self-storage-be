package com.storagehub.service.ledger;

import com.storagehub.domain.model.RenewalStatus;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled"},havingValue="true")
public class RenewalInstallmentNoticeScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RenewalInstallmentNoticeScheduler.class);
    private final EntityManager em;
    private final RenewalInstallmentService service;
    @Scheduled(fixedDelayString="${storagehub.renewal.installment-notices-ms:3600000}",initialDelayString="${storagehub.renewal.installment-notices-initial-ms:60000}")
    public void run(){
        for(int page=0;;page++){
            var ids=em.createQuery("select n.id from Renewal n where n.status=:s order by n.id",UUID.class).setParameter("s",RenewalStatus.completed).setFirstResult(page*100).setMaxResults(100).getResultList();
            for(var id:ids)try{service.publishDueNotices(id);}catch(RuntimeException unavailable){LOG.warn("Chưa thể gửi nhắc thanh toán kỳ của gia hạn {}",id);LOG.debug("Chi tiết lỗi nhắc thanh toán kỳ",unavailable);}
            if(ids.size()<100)break;
        }
    }
}
