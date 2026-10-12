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
    @Transactional(propagation=Propagation.MANDATORY)
    public void approved(RenewalWorkflow workflow,RenewalQuoteResponse.Terms terms) {
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
        ledger.charge(rental.getId(),workflow.getRenewal().getId(),spec.kind(),spec.amount(),spec.requireDue(workflow.getPaymentDeadline()),
            spec.sourceReference(),workflow.getReviewer().getId(),"renewal-deposit:"+workflow.getAcceptedRevision().getId(),account.revision());
    }
}
