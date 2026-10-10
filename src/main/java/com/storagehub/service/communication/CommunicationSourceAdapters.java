package com.storagehub.service.communication;

import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.support.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.communication.enabled",havingValue="true")
public class CommunicationSourceAdapters implements OverdueSources.ReminderSource,SupportSources.NotificationSource {
    private final NotificationOutbox outbox;private final CommunicationPolicyService policies;private final ObjectProvider<Clock> clocks;
    public boolean atomic(){return true;} // JDBC queue row joins the caller's local JPA datasource transaction.
    public boolean consistentThroughClose(){return true;} // Receipt immutable; close holds the ticket and reads the matching notice under lock.
    public Optional<OverdueSources.ReminderPolicy> policy(Rental rental){return policies.reminder(rental,now());}
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID enqueue(Rental rental,String caseRef,String content,String key,Instant now){
        var p=policies.reminder(rental,now).orElseThrow(()->com.storagehub.common.api.ApiExceptions.conflict("DEFERRED_SOURCE: Reminder policy missing"));
        String deliveryKey;
        try{deliveryKey=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((caseRef+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        return outbox.enqueue(rental.getCustomer().getId(),rental.getId(),"OVERDUE",null,p.reference(),p.version(),content,deliveryKey,now);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean enqueueReview(SupportTicket ticket,SupportState state,SupportEvent event,Instant now){
        if(event==null||!"RESOLVED".equals(event.getType())||!ticket.getId().equals(event.getTicket().getId())
            ||!Objects.equals(event.getRecordedAt(),state.getResolvedAt())||!Objects.equals(event.getActorId(),state.getResolvedBy())
            ||event.getAssignmentRevision()!=state.getAssignmentRevision())throw com.storagehub.common.api.ApiExceptions.conflict("Resolution notice identity is inconsistent");
        var rule=policies.read(ticket,state,now).orElse(null);if(rule==null)return false;
        outbox.enqueue(ticket.getCustomer().getId(),ticket.getId(),"SUPPORT_REVIEW",event.getId(),rule.policyRef(),rule.policyVersion(),ticket.getSubject(),event.getId().toString(),now);return true;
    }
    public Optional<SupportSources.ReviewNotice> reviewNotice(SupportTicket ticket,UUID event,SupportSources.CloseRule rule,Instant now){
        boolean lock=TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return outbox.review(ticket.getId(),event,rule.policyRef(),rule.policyVersion(),lock).filter(n->n.status().equals("ACKNOWLEDGED")&&n.recipient().equals(ticket.getCustomer().getId())&&n.acknowledgedAt()!=null&&!n.acknowledgedAt().isAfter(now)).map(n->new SupportSources.ReviewNotice(n.id(),ticket.getId(),n.recipient(),event,n.policyRef(),n.policyVersion(),n.acknowledgedAt()));
    }
    private Instant now(){var c=clocks.getIfAvailable();return(c==null?Clock.systemUTC():c).instant();}
}
