package com.storagehub.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.domain.model.*;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Converts an authenticated Renewal VNPay callback into one exact FULL_RENT subledger receipt. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class RenewalVnpaySettlementService {
    public record Result(boolean allocated,UUID receiptId,String reason) {}
    private final EntityManager em;
    private final LedgerStore ledger;
    private final ObjectMapper mapper;
    private final ObjectProvider<RenewalOperationSources.PolicySource> policies;
    private final ObjectProvider<RenewalOperationSources.CalendarSource> calendars;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safetySources;
    private final ObjectProvider<com.storagehub.service.renewal.RenewalSources.FinancialSource> financialSources;
    private final ObjectProvider<Clock> clocks;
    private final com.storagehub.service.PaymentProvenance provenance;

    @Transactional(propagation=Propagation.MANDATORY)
    public Result settle(Payment payment,UUID evidenceLogId) {
        return settleChecked(payment,evidenceLogId);
    }

    private Result settleChecked(Payment payment,UUID evidenceLogId) {
        if(payment!=null&&payment.getRenewalLedgerReceiptRef()!=null)
            return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
        if(payment==null||payment.getRenewal()==null||payment.getRenewalRevision()==null||evidenceLogId==null)
            return new Result(false,null,"Payment resource binding is incomplete");
        var renewal=em.find(Renewal.class,payment.getRenewal().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(renewal==null||renewal.getRental()==null||renewal.getRental().getCustomer()==null||renewal.getRental().getFacility()==null)
            return new Result(false,null,"Renewal source identity is incomplete");
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getAcceptedRevision()==null
            ||!Objects.equals(workflow.getAcceptedRevision().getId(),payment.getRenewalRevision().getId())
            ||renewal.getStatus()!=RenewalStatus.accepted)
            return new Result(false,null,"Payment no longer matches the current accepted Renewal revision");
        var revision=workflow.getAcceptedRevision();
        final RenewalQuoteResponse.Terms terms;
        try { terms=mapper.readValue(revision.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class); }
        catch (Exception invalid) { return new Result(false,null,"Accepted Renewal terms are invalid"); }
        final List<FinancialContractSpecifications.Specification> specifications;
        try { specifications=FinancialContractSpecifications.accepted(revision,terms); }
        catch (RuntimeException invalid) { return new Result(false,null,"Accepted Renewal terms failed financial validation"); }
        if(specifications.size()!=1||specifications.getFirst().kind()!=LedgerStore.Kind.RENEWAL_REMAINDER
            ||terms.renewalDepositAmount()==null||terms.renewalDepositAmount().signum()!=0
            ||terms.remainingRentalAmount().compareTo(terms.totalAfterDiscount())!=0
            ||payment.getPurpose()!=PaymentType.RENEWAL||payment.getStatus()!=PaymentStatus.PAID
            ||!"VNPAY".equals(payment.getGatewayProvider())||!"VND".equals(payment.getCurrency())
            ||payment.getGatewayTransactionNo()==null||payment.getGatewayTransactionNo().isBlank()
            ||payment.getPaidAt()==null||payment.getPaymentDeadline()==null
            ||!payment.getPaymentDeadline().equals(workflow.getPaymentDeadline())
            ||payment.getPaidAt().isAfter(payment.getPaymentDeadline())
            ||payment.getAmount()==null||payment.getAmount().compareTo(terms.totalAfterDiscount())!=0
            ||payment.getAmount().compareTo(specifications.getFirst().amount())!=0)
            return new Result(false,null,"Payment amount, purpose or deadline does not match full-rent Renewal terms");

        if(!Objects.equals(evidenceLogId,payment.getRenewalEvidenceRef())
            ||provenance.classify(payment)!=com.storagehub.service.PaymentProvenance.Classification.SANDBOX
            ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0)
            return new Result(false,null,"Verified resource-bound Payment evidence is unavailable");

        var now=now();
        if(payment.getPaidAt().isAfter(now))return new Result(false,null,"Gateway payment time is in the future");

        var account=ledger.read(renewal.getRental().getId()).orElse(null);
        if(account==null||!account.customerId().equals(renewal.getRental().getCustomer().getId())
            ||!account.facilityId().equals(renewal.getRental().getFacility().getId()))
            return new Result(false,null,"Renewal ledger account is unavailable or has changed binding");
        var spec=specifications.getFirst();
        var obligations=account.obligations().stream().filter(o->Objects.equals(o.renewalId(),renewal.getId())
            &&o.kind()==LedgerStore.Kind.RENEWAL_REMAINDER&&Objects.equals(o.sourceRef(),spec.sourceReference())
            &&o.amount().compareTo(spec.amount())==0).toList();
        if(obligations.size()!=1||obligations.getFirst().outstanding().compareTo(payment.getAmount())!=0)
            return new Result(false,null,"Unique unpaid full-rent obligation is unavailable");
        if(!ledger.renewalFullRentCommandMatches(obligations.getFirst(),spec,renewal.getRental().getCustomer().getId()))
            return new Result(false,null,"Full-rent obligation lacks the payment-ready writer evidence");
        UUID receipt=ledger.receiveSandbox(renewal.getRental().getId(),payment.getAmount(),
            Map.of(obligations.getFirst().id(),payment.getAmount()),
            new LedgerStore.GatewayProof(payment.getId(),evidenceLogId,payment.getGatewayTransactionNo()),
            renewal.getRental().getCustomer().getId(),"renewal-vnpay:"+payment.getId(),account.revision(),payment.getPaidAt());

        payment.setRenewalLedgerReceiptRef(receipt);
        payment.setRenewalAllocatedAt(now);
        em.flush();
        return new Result(true,receipt,null);
    }

    /** Transaction C: preserves the existing signing contract; does not extend Rental here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Result prepareSigning(Payment payment) {
        if(payment.getRenewalSigningPreparedAt()!=null)
            return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
        if(payment.getStatus()!=PaymentStatus.PAID||payment.getRenewalAllocatedAt()==null
            ||payment.getRenewalLedgerReceiptRef()==null||payment.getRenewalRevision()==null
            ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0
            ||provenance.classify(payment)!=com.storagehub.service.PaymentProvenance.Classification.SANDBOX)
            return new Result(false,null,"Verified full-rent allocation is required before signing");
        var renewal=em.find(Renewal.class,payment.getRenewal().getId(),LockModeType.PESSIMISTIC_WRITE);
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getAcceptedRevision()==null||renewal.getStatus()!=RenewalStatus.accepted
            ||!Objects.equals(workflow.getAcceptedRevision().getId(),payment.getRenewalRevision().getId()))
            return new Result(false,null,"Current accepted revision no longer permits signing preparation");
        final RenewalQuoteResponse.Terms terms;
        try { terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class); }
        catch(Exception invalid) { return new Result(false,null,"Accepted terms are invalid"); }
        var now=now();
        var safety=safetySources.getIfAvailable();var policySource=policies.getIfAvailable();var calendar=calendars.getIfAvailable();
        if(safety==null||!safety.atomic()||policySource==null||calendar==null||!calendar.atomic())
            return new Result(false,null,"Atomic signing and Recovery sources are unavailable");
        var policy=policySource.read(renewal).orElse(null);
        if(policy==null||policy.signingWindow()==null||policy.signingWindow().isNegative()||policy.signingWindow().isZero())
            return new Result(false,null,"Signing policy is unavailable");
        UUID hold=workflow.getExtensionHoldRef();
        Instant cutoff=safety.check(renewal,terms,hold,now);
        // Callback arrival time does not decide whether the customer paid before the deadline.
        if(cutoff==null||payment.getPaidAt().isAfter(payment.getPaymentDeadline())||payment.getPaymentDeadline().isAfter(cutoff))
            return new Result(false,null,"Extension rights require reconciliation");
        Instant signingDeadline=min(payment.getPaidAt().plus(policy.signingWindow()),cutoff);
        if(!signingDeadline.isAfter(now)||!calendar.feasible(renewal,now,signingDeadline))
            return new Result(false,null,"No signing slot is available before the Recovery cutoff");
        var financial=financialSources.getIfAvailable();
        var financialState=financial==null?null:financial.read(renewal.getRental()).orElse(null);
        if(financial==null||!financial.consistentThroughApproval()||financialState==null||financialState.checkedAt()==null
            ||financialState.checkedAt().isAfter(now)||financialState.dueObligations()==null
            ||financialState.unresolvedDispute()||!financialState.dueObligations().isEmpty())
            return new Result(false,null,"Financial state is unknown or still blocks signing");
        var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(state!=null&&state.hasVerifiedPayment())
            return new Result(false,null,"Renewal already has a different verified payment state");
        safety.retain(renewal,hold,signingDeadline);

        boolean newState=state==null;
        if(newState)state=new RenewalOperationState(renewal);
        state.fullRent(payment.getId(),payment.getPaidAt(),signingDeadline,cutoff,policy.reference(),policy.version());
        if(newState)em.persist(state);
        renewal.setStatus(RenewalStatus.full_rent_paid);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        var eventData=Map.of("paymentRef",payment.getId(),"amount",payment.getAmount(),"currency",payment.getCurrency(),
            "paidAt",payment.getPaidAt(),"paymentDeadline",payment.getPaymentDeadline(),"receiptId",payment.getRenewalLedgerReceiptRef());
        try {
            em.persist(new RenewalOperationEvent(renewal,"FULL_RENT_PAYMENT",payment.getPaidAt(),null,mapper.writeValueAsString(eventData)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Could not store Renewal payment event",invalid);
        }
        em.flush();
        payment.setRenewalSigningPreparedAt(now);
        payment.setFailureCode(null);payment.setFailureReason(null);payment.setRenewalNextRetryAt(null);
        return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
    }

    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
}
