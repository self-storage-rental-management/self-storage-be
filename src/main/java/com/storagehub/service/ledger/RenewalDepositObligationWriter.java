package com.storagehub.service.ledger;

import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.RenewalStatus;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Approval is the effective deposit event; the persisted approval deadline is its due timestamp. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled"},havingValue="true")
public class RenewalDepositObligationWriter {
    private final LedgerStore ledger;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<RenewalLedgerAdjustments> cancellationCredits;

    /** Cancel only unpaid charges belonging to the closing accepted revision. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelUnpaid(RenewalWorkflow workflow) {
        var renewal=workflow.getRenewal();
        var account=ledger.read(renewal.getRental().getId()).orElse(null);
        if(account==null)return;
        for(var obligation:account.obligations()) {
            if(!java.util.Objects.equals(obligation.renewalId(),renewal.getId())||obligation.outstanding().signum()==0)continue;
            if(workflow.getAcceptedRevision()==null||!obligation.sourceRef().startsWith("renewal-accepted:"+workflow.getAcceptedRevision().getId()+":"))
                throw ApiExceptions.conflict("Unpaid Renewal charge has an unexpected revision binding");
            if(obligation.outstanding().compareTo(obligation.amount())!=0)
                throw ApiExceptions.conflict("Partially settled Renewal charge requires reconciliation");
            var credits=cancellationCredits.getIfAvailable();
            if(credits==null)throw ApiExceptions.conflict("Cancellation accounting source unavailable");
            credits.cancelOutstanding(account,obligation,obligation.outstanding(),
                "void-unpaid:"+renewal.getId()+":"+obligation.id(),"UNPAID_RENEWAL_CLOSED");
            account=ledger.read(renewal.getRental().getId()).orElseThrow();
        }
    }

    /** Posts the accepted rental obligations when the authenticated Sandbox intent is made available. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void paymentIntentAvailable(RenewalWorkflow workflow,RenewalQuoteResponse.Terms terms,java.util.UUID customerId) {
        if(workflow==null||workflow.getRenewal()==null||workflow.getAcceptedRevision()==null
            ||workflow.getPaymentDeadline()==null||customerId==null
            ||!customerId.equals(workflow.getRenewal().getRental().getCustomer().getId()))
            throw ApiExceptions.conflict("System-owned Renewal payment readiness is incomplete");
        var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
        var renewal=workflow.getRenewal();var rental=renewal.getRental();
        if(!specs.getFirst().rentalId().equals(rental.getId())||!specs.getFirst().renewalId().equals(renewal.getId()))
            throw ApiExceptions.conflict("Accepted revision does not belong to this Renewal");
        var account=ledger.read(rental.getId()).orElseThrow(()->ApiExceptions.conflict("Ledger account missing for Renewal payment"));
        if(!account.customerId().equals(rental.getCustomer().getId())||!account.facilityId().equals(rental.getFacility().getId()))
            throw ApiExceptions.conflict("Ledger source binding changed");
        for(var spec:specs) {
            var current=ledger.read(rental.getId()).orElseThrow();
            var existing=current.obligations().stream().filter(o->spec.sourceReference().equals(o.sourceRef())).toList();
            if(!existing.isEmpty()) {
                if(existing.size()!=1 || !renewal.getId().equals(existing.getFirst().renewalId())
                    || existing.getFirst().kind()!=spec.kind() || existing.getFirst().amount().compareTo(spec.amount())!=0
                    || Math.abs(existing.getFirst().dueAt().toEpochMilli() - spec.requireDue(workflow.getPaymentDeadline(),workflow.getSigningBalanceDueAt()).toEpochMilli()) > 2000)
                    throw ApiExceptions.conflict("Khoản thanh toán gia hạn đã thay đổi. Cần đối soát lại trước khi tiếp tục.");
                continue;
            }
            ledger.charge(rental.getId(),renewal.getId(),spec.kind(),spec.amount(),
                spec.requireDue(workflow.getPaymentDeadline(),workflow.getSigningBalanceDueAt()),spec.sourceReference(),customerId,
                paymentKey(spec,workflow.getAcceptedRevision().getId()),current.revision());
        }
    }

    static String paymentKey(FinancialContractSpecifications.Specification spec,java.util.UUID revision) {
        if(spec.dueDate()!=null)return "renewal-month:"+revision+":"+spec.dueDate();
        return (spec.kind()==LedgerStore.Kind.RENEWAL_DEPOSIT?"renewal-prepayment:":
            spec.sourceReference().endsWith(":FULL_RENT")?"renewal-full-rent:":"renewal-remainder:")+revision;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void approved(RenewalWorkflow workflow,RenewalQuoteResponse.Terms terms) {
        if (terms != null && terms.renewalDepositAmount() != null && terms.renewalDepositAmount().signum() == 0)
            throw ApiExceptions.conflict("Legacy approval-backed deposit writer cannot post new initial rental Renewal obligations");
        if(workflow==null||workflow.getRenewal()==null||workflow.getRenewal().getStatus()!=RenewalStatus.approved
            ||workflow.getReviewer()==null||workflow.getReviewedAt()==null||workflow.getPaymentDeadline()==null
            ||!workflow.getPaymentDeadline().isAfter(workflow.getReviewedAt())||workflow.getExtensionHoldRef()==null)
            throw ApiExceptions.conflict("Authoritative Renewal approval and deadline required");
        var spec=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms).getFirst();
        var rental=workflow.getRenewal().getRental();
        if(!spec.rentalId().equals(rental.getId())||!spec.renewalId().equals(workflow.getRenewal().getId()))
            throw ApiExceptions.conflict("Accepted revision does not belong to the approved Renewal");
        var account=ledger.read(rental.getId()).orElseThrow(()->ApiExceptions.conflict("Ledger account missing for Renewal obligation"));
        if(!account.customerId().equals(rental.getCustomer().getId())||!account.facilityId().equals(rental.getFacility().getId()))
            throw ApiExceptions.conflict("Ledger source binding changed");
        ledger.charge(rental.getId(),workflow.getRenewal().getId(),spec.kind(),spec.amount(),spec.requireDue(workflow.getPaymentDeadline(),workflow.getSigningBalanceDueAt()),
            spec.sourceReference(),workflow.getReviewer().getId(),"renewal-deposit:"+workflow.getAcceptedRevision().getId(),account.revision());
    }
}
