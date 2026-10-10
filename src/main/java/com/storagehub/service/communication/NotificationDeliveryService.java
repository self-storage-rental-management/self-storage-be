package com.storagehub.service.communication;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.NotificationService;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class NotificationDeliveryService {
    public record Acknowledgement(UUID notificationId,String status,Instant receivedAt) {}
    public record AcknowledgementState(UUID notificationId,String status,boolean acknowledgementAllowed,Instant receivedAt) {}
    private final NotificationOutbox outbox;private final NotificationService notifications;
    private final EntityManager em;private final DuongResourceAccess access;
    private final PlatformTransactionManager transactions;private final ObjectProvider<Clock> clocks;
    /** Durable retries across process restarts. No external email/network side effects in a DB transaction. */
    @Scheduled(initialDelayString="${storagehub.integration.communication.worker-initial-delay-ms:60000}",fixedDelayString="${storagehub.integration.communication.worker-delay-ms:30000}")
    public void dispatch(){for(var id:outbox.pending(now()))deliver(id);}
    public void deliver(UUID id){
        var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try{tx.executeWithoutResult(s->{var n=outbox.read(id,true).orElse(null);if(n==null||!n.status().equals("PENDING")||n.retryAt().isAfter(now()))return;
            var user=em.find(User.class,n.recipient());if(user==null||user.getStatus()!=UserStatus.ACTIVE)throw ApiExceptions.conflict("Notification recipient unavailable");
            var created=notifications.createNotification(n.recipient(),n.kind().equals("OVERDUE")?NotificationType.OVERDUE:NotificationType.SUPPORT,n.kind().equals("OVERDUE")?"Nhắc nhở hồ sơ quá hạn":"Yêu cầu hỗ trợ đã được xử lý, vui lòng xem xét",n.content(),n.resource());
            outbox.inbox(id,created.getId());em.flush();
        });}catch(RuntimeException failure){tx.executeWithoutResult(s->outbox.retry(id,now()));} // Stores only attempt/time, not private/raw exception text.
    }
    @Transactional(readOnly=true) public AcknowledgementState acknowledgementState(ActorPrincipal actor,UUID notificationId){
        access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
        var n=em.find(Notification.class,notificationId);if(n==null||!n.getUser().getId().equals(actor.userId()))throw ApiExceptions.notFound("Notification not found");
        var id=outbox.forNotification(notificationId,actor.userId());
        if(id.isEmpty())return new AcknowledgementState(notificationId,"UNTRACKED",false,null);
        var receipt=outbox.read(id.get(),false).orElseThrow(()->ApiExceptions.conflict("Notification tracking unavailable"));
        if(!receipt.recipient().equals(actor.userId())||!notificationId.equals(receipt.notificationId())||!Objects.equals(receipt.resource(),n.getRelatedEntityId())
            ||(receipt.kind().equals("SUPPORT_REVIEW")?n.getType()!=NotificationType.SUPPORT:n.getType()!=NotificationType.OVERDUE))
            throw ApiExceptions.conflict("Notification tracking identity inconsistent");
        if(receipt.status().equals("INBOX")&&receipt.acknowledgedAt()==null)return new AcknowledgementState(notificationId,"AVAILABLE",true,null);
        if(receipt.status().equals("ACKNOWLEDGED")&&receipt.acknowledgedAt()!=null&&!receipt.acknowledgedAt().isAfter(now()))return new AcknowledgementState(notificationId,"ACKNOWLEDGED",false,receipt.acknowledgedAt());
        throw ApiExceptions.conflict("Notification tracking state inconsistent");
    }
    @Transactional public Acknowledgement acknowledge(ActorPrincipal actor,UUID notificationId){
        access.require(actor,RoleCode.CUSTOMER,null,FacilityScopeLevel.READ);
        var n=em.find(Notification.class,notificationId);if(n==null||!n.getUser().getId().equals(actor.userId()))throw ApiExceptions.notFound("Notification not found");
        UUID id=outbox.forNotification(notificationId,actor.userId()).orElseThrow(()->ApiExceptions.notFound("Tracked notification not found"));
        var receipt=outbox.acknowledge(id,actor.userId(),notificationId,now());
        return new Acknowledgement(notificationId,receipt.status(),receipt.acknowledgedAt());
    }
    private Instant now(){var c=clocks.getIfAvailable();return(c==null?Clock.systemUTC():c).instant();}
}
