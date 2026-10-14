package com.storagehub.service.renewal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.RenewalSigningDeadline;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Opt-in local correction preserves accepted prices and authenticated funds. */
@Service @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name="storagehub.integration.renewal-signing-window-correction.enabled",havingValue="true")
public class RenewalSigningWindowCorrectionService {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final PaymentProvenance provenance;
    private final RenewalLocalPaymentChecks checks;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safetySources;
    private final AuditLogService audit;

    @Transactional(readOnly=true)
    public List<UUID> candidates() {
        return em.createQuery("select s.id from RenewalOperationState s where s.policyRef='RENEWAL_SELECTED_APPOINTMENT' and s.policyVersion='1' and s.phase in :phases order by s.id",UUID.class)
            .setParameter("phases",List.of(RenewalOperationState.Phase.SIGNING,RenewalOperationState.Phase.SIGNING_EXPIRED)).setMaxResults(100).getResultList();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean correct(UUID id) {
        var renewal=em.find(Renewal.class,id);
        if(renewal==null)return false;
        var rental=em.find(Rental.class,renewal.getRental().getId(),LockModeType.PESSIMISTIC_WRITE);em.refresh(rental,LockModeType.PESSIMISTIC_WRITE);
        em.refresh(renewal,LockModeType.PESSIMISTIC_WRITE);
        var workflow=em.find(RenewalWorkflow.class,id,LockModeType.PESSIMISTIC_WRITE);
        var state=em.find(RenewalOperationState.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||state==null||!state.hasVerifiedPayment()||!"RENEWAL_SELECTED_APPOINTMENT".equals(state.getPolicyRef())||!"1".equals(state.getPolicyVersion())
            ||!Set.of(RenewalOperationState.Phase.SIGNING,RenewalOperationState.Phase.SIGNING_EXPIRED).contains(state.getPhase())
            ||state.getCompletedAt()!=null||state.getConfirmedExceptionRef()!=null||state.getPendingExceptionRef()!=null
            ||!Set.of(RenewalStatus.appointment_scheduled,RenewalStatus.deposit_paid,RenewalStatus.full_rent_paid).contains(renewal.getStatus())
            ||!Objects.equals(state.getOriginalSigningDeadline(),state.getAppointmentEnd())
            ||!Objects.equals(state.getEffectiveSigningDeadline(),state.getOriginalSigningDeadline()))return false;
        UUID paymentId=state.getDepositPaymentRef()!=null?state.getDepositPaymentRef():state.getFullRentPaymentRef();
        if(paymentId==null)throw ApiExceptions.conflict("Signing payment binding missing");
        var payment=em.find(Payment.class,paymentId,LockModeType.PESSIMISTIC_WRITE);
        Instant paid=state.getDepositPaidAt()!=null?state.getDepositPaidAt():state.getFullRentPaidAt();
        if(payment==null||payment.getStatus()!=PaymentStatus.PAID||payment.getPurpose()!=PaymentType.RENEWAL
            ||payment.getRenewal()==null||!payment.getRenewal().getId().equals(id)||payment.getRenewalRevision()==null
            ||!payment.getRenewalRevision().getId().equals(workflow.getAcceptedRevision().getId())||!Objects.equals(payment.getPaidAt(),paid)
            ||payment.getRenewalAllocatedAt()==null||payment.getRenewalSigningPreparedAt()==null
            ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0||provenance.classify(payment)!=PaymentProvenance.Classification.SANDBOX)
            throw ApiExceptions.conflict("Authenticated allocated payment required for signing correction");
        Instant now=Instant.now(),cutoff=checks.cutoff(rental,now),deadline=RenewalSigningDeadline.calculate(paid,cutoff);
        if(!deadline.isAfter(now))return false;
        final RenewalQuoteResponse.Terms terms;
        try{terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Accepted signing correction terms invalid");}
        checks.requireKnownFinance(rental,id,now);
        var safety=safetySources.getIfAvailable();
        if(safety==null||!safety.atomic()||!safety.restoreSigningWindow(renewal,terms,workflow.getExtensionHoldRef(),deadline))
            throw ApiExceptions.conflict("Signing hold cannot be restored for correction");
        var before=Map.of("phase",state.getPhase(),"deadline",state.getEffectiveSigningDeadline());
        state.correctSigningWindow(deadline,cutoff,RenewalSigningDeadline.REFERENCE,RenewalSigningDeadline.VERSION);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        audit.recordMutation((User)null,"RENEWAL_SIGNING_WINDOW_CORRECTED_V2","Renewal",id,rental.getFacility().getId(),before,
            Map.of("deadline",deadline,"paymentId",paymentId,"paidAt",paid,"cutoff",cutoff,"reason","Separate three-day signing window from appointment duration"));
        return true;
    }
}
