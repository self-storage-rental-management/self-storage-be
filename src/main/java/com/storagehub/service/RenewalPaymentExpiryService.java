package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queries expired Renewal intents before releasing their capacity hold or open workflow slot. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled",havingValue="true")
public class RenewalPaymentExpiryService {
    private static final List<PaymentStatus> UNRESOLVED=List.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED);
    private final PaymentRepository payments;
    private final VnpayService vnpay;
    private final EntityManager em;
    private final RenewalPersistence renewals;
    private final ObjectMapper mapper;
    private final AuditLogService audit;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safetySources;

    @Transactional
    public int reconcileExpiredIntents() {
        Instant now=Instant.now();
        var candidates=new ArrayList<Payment>();
        for(PaymentStatus status:UNRESOLVED)candidates.addAll(payments.findByStatus(status));
        candidates.addAll(payments.findByStatus(PaymentStatus.FAILED));
        int completed=0;
        for(Payment candidate:candidates) {
            if(candidate.getRenewal()==null||candidate.getPurpose()!=PaymentType.RENEWAL
                ||candidate.getPaymentDeadline()==null||candidate.getPaymentDeadline().isAfter(now))continue;
            Payment payment=payments.findByIdForUpdate(candidate.getId()).orElse(null);
            if(payment==null||payment.getRenewal()==null||payment.getPurpose()!=PaymentType.RENEWAL
                ||payment.getPaymentDeadline()==null||payment.getPaymentDeadline().isAfter(now))continue;
            if(UNRESOLVED.contains(payment.getStatus())) {
                VnpayService.ReconciliationOutcome outcome=vnpay.reconcileAtPaymentDeadline(payment);
                payments.saveAndFlush(payment);
                if(outcome!=VnpayService.ReconciliationOutcome.FINAL_FAILURE)continue;
            } else if(payment.getStatus()!=PaymentStatus.FAILED)continue;
            if(expireIfSafe(payment.getRenewal(),payment))completed++;
        }
        return completed;
    }

    private boolean expireIfSafe(Renewal paymentRenewal,Payment payment) {
        Renewal renewal=em.find(Renewal.class,paymentRenewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(renewal==null||renewal.getStatus()!=RenewalStatus.accepted)return false;
        if(payments.existsByRenewal_IdAndStatusIn(renewal.getId(),UNRESOLVED))return false;
        if(payments.findAllByRenewal_IdAndStatusIn(renewal.getId(),List.of(PaymentStatus.PAID)).size()>0)return false;
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getPaymentDeadline()==null||workflow.getPaymentDeadline().isAfter(Instant.now())
            ||workflow.getExtensionHoldRef()==null)return false;
        var safety=safetySources.getIfAvailable();
        if(safety==null||!safety.atomic())return false;
        safety.release(renewal,workflow.getExtensionHoldRef());
        renewal.setStatus(RenewalStatus.payment_expired);
        var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(state==null){state=new RenewalOperationState(renewal);em.persist(state);}
        state.expire(false);
        renewals.releaseSlot(workflow);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        var payload=Map.of("paymentId",payment.getId(),"responseCode",Objects.toString(payment.getGatewayResponseCode(),"UNKNOWN"),
            "expiredAt",Instant.now().toString(),"holdReleased",true);
        try { em.persist(new RenewalOperationEvent(renewal,"EXPIRY",Instant.now(),null,mapper.writeValueAsString(payload))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Could not persist Renewal payment expiry event",invalid);}
        audit.recordMutation((User)null,"RENEWAL_PAYMENT_EXPIRED","Renewal",renewal.getId(),renewal.getRental().getFacility().getId(),
            Map.of("status",RenewalStatus.accepted),Map.of("status",RenewalStatus.payment_expired,"paymentId",payment.getId()));
        return true;
    }
}
