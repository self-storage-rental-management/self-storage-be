package com.storagehub.service.overdue;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.NotificationService;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Automatic handoff to the persisted Recovery task receiver. */
@Service @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.renewal-auto-recovery.enabled",
    "storagehub.integration.recovery-tasks.enabled"},havingValue="true")
public class LocalRenewalAutoRecoveryService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private final EntityManager em;
    private final RentalPeriodResolver periods;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    private final ObjectProvider<OverdueSources.RecoverySource> recoveries;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safeties;
    private final ObjectProvider<RenewalOperationSources.CalendarSource> calendars;
    private final ObjectProvider<Clock> clocks;
    private final NotificationService notifications;
    private final AuditLogService audit;

    @Transactional(readOnly=true)
    public List<UUID> candidates() {
        var clock=clocks.getIfAvailable();
        var oldest=(clock==null?Clock.systemUTC():clock).instant().atZone(ZONE).toLocalDate().minusDays(7);
        // The stored end can be inclusive or exclusive; the resolver checks the exact day under lock.
        return em.createQuery("select r.id from Rental r where r.status=:active and r.actualReturnedAt is null "
            +"and r.contractEndDate<=:oldest and not exists "
            +"(select f.id from OverdueFollowUp f where f.rental=r and f.type='RECOVERY_HANDOFF') "
            +"order by r.contractEndDate,r.id",UUID.class)
            .setParameter("active",RentalStatus.active).setParameter("oldest",oldest).getResultList();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean handoff(UUID id) {
        var clock=clocks.getIfAvailable();Instant now=(clock==null?Clock.systemUTC():clock).instant();
        var rental=em.find(Rental.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(rental==null)return false;
        em.refresh(rental,LockModeType.PESSIMISTIC_WRITE);
        if(rental.getStatus()!=RentalStatus.active||rental.getActualReturnedAt()!=null||rental.getCompletedAt()!=null)return false;
        var period=periods.read(rental);
        var termSource=terms.getIfAvailable();
        var term=termSource==null?Optional.<OverdueSources.Term>empty():termSource.term(rental,now);
        if(period.isEmpty()||term.isEmpty())throw ApiExceptions.conflict("DEFERRED_SOURCE: Rental period or published overdue term missing");
        var t=term.get();
        if(t.policyRef()==null||t.policyRef().isBlank()||t.policyVersion()==null||t.policyVersion().isBlank()
            ||!Objects.equals(t.lastPermittedDate(),period.get().lastPermittedDate())
            ||t.warningThroughDay()!=3||t.seriousThroughDay()!=6||t.urgentThroughDay()!=7||t.recoveryFromDay()!=8
            ||t.recoveryCutoff()==null||t.recoveryStart()==null||!t.recoveryStart().isAfter(t.recoveryCutoff()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Overdue policy/date mapping inconsistent");
        if(now.isBefore(t.recoveryStart()))return false;
        String ref="RENTAL_TERM:"+id;
        if(!em.createQuery("select f.id from OverdueFollowUp f where f.caseRef=:ref and f.type='RECOVERY_HANDOFF'",UUID.class)
            .setParameter("ref",ref).setMaxResults(1).getResultList().isEmpty())return false;
        var receiver=recoveries.getIfAvailable();
        if(receiver==null||!receiver.atomic()||!RecoveryTaskSource.class.isAssignableFrom(
            org.springframework.aop.support.AopUtils.getTargetClass(receiver)))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery task receiver unavailable");
        var renewals=em.createQuery("select n from Renewal n where n.rental.id=:id order by n.id",Renewal.class)
            .setParameter("id",id).getResultList();
        for(var renewal:renewals) {
            // Earlier completed renewals belong to prior rental periods; the current Rental period is authoritative.
            if(Set.of(RenewalStatus.completed,RenewalStatus.cancelled,RenewalStatus.rejected,
                RenewalStatus.payment_expired).contains(renewal.getStatus()))continue;
            long uncertain=em.createQuery("select count(p) from Payment p where p.renewal.id=:id and p.status in :statuses",Long.class)
                .setParameter("id",renewal.getId()).setParameter("statuses",List.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED))
                .getSingleResult();
            if(uncertain>0)throw ApiExceptions.conflict("DEFERRED_SOURCE: Renewal Payment requires reconciliation before Recovery handoff");
            var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
            long paid=em.createQuery("select count(p) from Payment p where p.renewal.id=:id and p.status=:paid",Long.class)
                .setParameter("id",renewal.getId()).setParameter("paid",PaymentStatus.PAID).getSingleResult();
            if(paid>0&&(state==null||!state.hasVerifiedPayment()))
                throw ApiExceptions.conflict("DEFERRED_SOURCE: Paid Renewal operation state is not reconciled");
            if(state!=null&&state.getPhase()==RenewalOperationState.Phase.COMPLETED)
                throw ApiExceptions.conflict("Active Renewal has a completed operation state");
        }
        var safety=safeties.getIfAvailable();
        if(safety==null||!safety.atomic())throw ApiExceptions.conflict("DEFERRED_SOURCE: Extension hold release unavailable");
        var handoff=receiver.receive(rental,ref,"AUTO_OVERDUE_DAY_8",ref,now);
        if(handoff==null||handoff.reference()==null||!Set.of("RECEIVED","ALREADY_RECEIVED").contains(handoff.status()))
            throw ApiExceptions.conflict("Recovery receiver did not acknowledge");
        for(var renewal:renewals) {
            if(Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.payment_expired,RenewalStatus.completed).contains(renewal.getStatus()))continue;
            var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
            var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
            if(workflow!=null&&workflow.getExtensionHoldRef()!=null)safety.release(renewal,workflow.getExtensionHoldRef());
            if(state==null){state=new RenewalOperationState(renewal);em.persist(state);}
            boolean paid=state.hasVerifiedPayment();
            state.expire(paid);renewal.setStatus(RenewalStatus.cancelled);
            if(workflow!=null)workflow.cancelled("AUTO_RECOVERY_DAY_8");
            var calendar=calendars.getIfAvailable();if(calendar!=null)calendar.release(renewal,now);
            em.persist(new RenewalOperationEvent(renewal,"RECOVERY_CANCELLATION",now,null,
                "{\"reason\":\"AUTO_RECOVERY_DAY_8\",\"depositDisposition\":\"REVIEW_REQUIRED\"}"));
        }
        var followUp=new OverdueFollowUp(ref,rental,"RECOVERY_HANDOFF","AUTO_OVERDUE_DAY_8",null,now,
            handoff.reference(),t.policyRef(),t.policyVersion());
        em.persist(followUp);
        var stream=em.find(OverdueFollowUpState.class,ref,LockModeType.PESSIMISTIC_WRITE);
        if(stream==null){stream=new OverdueFollowUpState(ref);em.persist(stream);}stream.appended();
        notifications.createNotification(rental.getCustomer().getId(),NotificationType.OVERDUE,
            "Hồ sơ thuê đã chuyển sang xử lý thu hồi",
            "Hợp đồng đã quá hạn 7 ngày. Vui lòng liên hệ cơ sở để xử lý hợp đồng, tài sản và tiền cọc.",id);
        audit.recordMutation((User)null,"AUTO_RECOVERY_HANDOFF","Rental",id,rental.getFacility().getId(),null,
            Map.of("caseRef",ref,"receiverRef",handoff.reference(),"policyRef",t.policyRef(),"policyVersion",t.policyVersion(),
                "renewalCancellationCount",renewals.size(),"depositDisposition","REVIEW_REQUIRED"));
        return true;
    }
}
