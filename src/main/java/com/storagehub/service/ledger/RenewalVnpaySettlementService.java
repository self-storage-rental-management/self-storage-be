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

    @Transactional(propagation=Propagation.MANDATORY)
    public Result settle(Payment payment,UUID evidenceLogId) {
        return settleChecked(payment,evidenceLogId);
    }

    private Result settleChecked(Payment payment,UUID evidenceLogId) {
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

        var now=now();
        if(payment.getPaidAt().isAfter(now))return new Result(false,null,"Gateway payment time is in the future");
        var safety=safetySources.getIfAvailable();var policySource=policies.getIfAvailable();var calendar=calendars.getIfAvailable();
        if(safety==null||!safety.atomic()||policySource==null||calendar==null||!calendar.atomic())
            return new Result(false,null,"Atomic signing and Recovery sources are unavailable");
        var policy=policySource.read(renewal).orElse(null);
        if(policy==null||policy.signingWindow()==null||policy.signingWindow().isNegative()||policy.signingWindow().isZero())
            return new Result(false,null,"Signing policy is unavailable");
        UUID hold=workflow.getExtensionHoldRef();
        Instant cutoff=safety.check(renewal,terms,hold,now);
        if(cutoff==null||now.isAfter(workflow.getPaymentDeadline())||payment.getPaymentDeadline().isAfter(cutoff))
            return new Result(false,null,"Payment deadline or extension hold is no longer active");
        Instant signingDeadline=min(payment.getPaidAt().plus(policy.signingWindow()),cutoff);
        if(!signingDeadline.isAfter(now)||!calendar.feasible(renewal,now,signingDeadline))
            return new Result(false,null,"No signing slot is available before the Recovery cutoff");

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
        var financial=financialSources.getIfAvailable();
        if(financial==null||!financial.consistentThroughApproval())
            return new Result(false,null,"Consistent Renewal financial source is unavailable");
        var financialState=financial.read(renewal.getRental()).orElse(null);
        if(financialState==null||financialState.checkedAt()==null||financialState.checkedAt().isAfter(now)
            ||financialState.dueObligations()==null||financialState.unresolvedDispute()
            ||financialState.dueObligations().stream().anyMatch(ref->!Objects.equals(ref,obligations.getFirst().id())))
            return new Result(false,null,"Other due obligations or a financial dispute block Renewal payment allocation");
        var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(state!=null&&state.hasVerifiedPayment())
            return new Result(false,null,"Renewal already has a verified payment state");

        safety.retain(renewal,hold,signingDeadline);
        UUID receipt=ledger.receiveSandbox(renewal.getRental().getId(),payment.getAmount(),
            Map.of(obligations.getFirst().id(),payment.getAmount()),
            new LedgerStore.GatewayProof(payment.getId(),evidenceLogId,payment.getGatewayTransactionNo()),
            renewal.getRental().getCustomer().getId(),"renewal-vnpay:"+payment.getId(),account.revision(),payment.getPaidAt());

        if(state==null) { state=new RenewalOperationState(renewal);em.persist(state); }
        state.fullRent(payment.getId(),payment.getPaidAt(),signingDeadline,cutoff,policy.reference(),policy.version());
        renewal.setStatus(RenewalStatus.full_rent_paid);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        var eventData=Map.of("paymentRef",payment.getId(),"amount",payment.getAmount(),"currency",payment.getCurrency(),
            "paidAt",payment.getPaidAt(),"paymentDeadline",payment.getPaymentDeadline(),"receiptId",receipt);
        try {
            em.persist(new RenewalOperationEvent(renewal,"FULL_RENT_PAYMENT",payment.getPaidAt(),null,mapper.writeValueAsString(eventData)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Could not store Renewal payment event",invalid);
        }
        em.flush();
        return new Result(true,receipt,null);
    }

    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
}
