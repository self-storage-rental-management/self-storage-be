package com.storagehub.service.ledger;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.RenewalSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import com.storagehub.api.renewal.RenewalQuoteResponse.Terms;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Reads shared Payment proof and exact Ledger allocations at completion. Does not execute payments. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled",
    "storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class RenewalLedgerAccountingConfiguration {
    @Bean @ConditionalOnMissingBean(RenewalOperationSources.AccountingSource.class)
    RenewalOperationSources.AccountingSource renewalLedgerAccounting(EntityManager em,LedgerStore ledger,
        PaymentRepository payments,PaymentProvenance provenance,ObjectProvider<RenewalSources.FinancialSource> finance) {
        return new Accounting(em,ledger,payments,provenance,finance);
    }

    static class Accounting implements RenewalOperationSources.AccountingSource {
        private final EntityManager em;private final LedgerStore ledger;private final PaymentRepository payments;
        private final PaymentProvenance provenance;private final ObjectProvider<RenewalSources.FinancialSource> finance;
        Accounting(EntityManager em,LedgerStore ledger,PaymentRepository payments,PaymentProvenance provenance,
            ObjectProvider<RenewalSources.FinancialSource> finance) {
            this.em=em;this.ledger=ledger;this.payments=payments;this.provenance=provenance;this.finance=finance;
        }
        @Override public boolean atomic(){return true;}
        @Override public RenewalOperationSources.Deposit deposit(Renewal renewal,Terms terms,UUID actor,String key,Instant deadline) {
            throw deferred("Legacy deposit execution must use its original Payment owner adapter");
        }
        @Override public boolean safeToExpire(Renewal renewal) {
            var attempts=payments.findAllByRenewal_IdOrderByCreatedAtAsc(renewal.getId());
            return !attempts.isEmpty()&&attempts.stream().allMatch(p->p.getStatus()==PaymentStatus.FAILED
                &&p.getLastReconciledAt()!=null);
        }
        @Override public RenewalOperationSources.Statement statement(Renewal renewal,Terms terms,Instant now) {
            throw deferred("CASH payable statement remains the cash receipt owner's responsibility");
        }
        @Override public RenewalOperationSources.Receipt cash(Renewal renewal,UUID statement,String receipt,UUID actor,String key,Instant now) {
            throw deferred("Actual CASH recording requires the cash receipt owner's verified evidence");
        }
        @Override public void requireFullyPaid(Renewal renewal,Terms terms,Instant now) {
            var workflow=em.find(RenewalWorkflow.class,renewal.getId(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if(workflow==null||workflow.getAcceptedRevision()==null||workflow.getPaymentDeadline()==null)
                throw deferred("Accepted Renewal revision and payment deadline are unavailable");
            var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
            if(specs.size()!=1||terms.renewalDepositAmount().signum()!=0)throw deferred("Historical split-payment terms require their original accounting adapter");
            var snapshot=ledger.read(renewal.getRental().getId()).orElseThrow(()->deferred("Renewal Ledger account is unknown"));
            if(!snapshot.customerId().equals(renewal.getRental().getCustomer().getId())||!snapshot.facilityId().equals(renewal.getRental().getFacility().getId()))
                throw deferred("Ledger resource binding changed");
            var spec=specs.getFirst();var obligations=snapshot.obligations().stream().filter(o->
                Objects.equals(o.renewalId(),renewal.getId())&&o.kind()==spec.kind()&&o.sourceRef().equals(spec.sourceReference())
                &&o.amount().compareTo(spec.amount())==0&&o.dueAt().toEpochMilli()==workflow.getPaymentDeadline().toEpochMilli()).toList();
            if(obligations.size()!=1||obligations.getFirst().outstanding().signum()!=0||obligations.getFirst().refunded().signum()!=0)
                throw deferred("Full-period obligation is missing, unpaid or refunded");
            var candidates=payments.findAllByRenewal_IdAndStatusIn(renewal.getId(),List.of(PaymentStatus.PAID));
            boolean verified=false;
            for(var candidate:candidates) {
                var payment=payments.findByIdForUpdate(candidate.getId()).orElse(null);
                if(payment==null||payment.getRenewalRevision()==null||!payment.getRenewalRevision().getId().equals(workflow.getAcceptedRevision().getId())
                    ||payment.getPurpose()!=PaymentType.RENEWAL||payment.getRenewalEvidenceRef()==null
                    ||payment.getRenewalLedgerReceiptRef()==null||payment.getRenewalSigningPreparedAt()==null
                    ||payment.getAmount().compareTo(spec.amount())!=0||!"VND".equals(payment.getCurrency())
                    ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0
                    ||provenance.classify(payment)!=PaymentProvenance.Classification.SANDBOX)continue;
                var receipts=snapshot.receipts().stream().filter(r->r.id().equals(payment.getRenewalLedgerReceiptRef())
                    &&("payment:"+payment.getId()).equals(r.evidenceRef())&&r.method()==LedgerStore.Method.VNPAY_SANDBOX
                    &&r.amount().compareTo(spec.amount())==0&&r.receivedAt().toEpochMilli()==payment.getPaidAt().toEpochMilli()).toList();
                verified=receipts.size()==1&&ledger.renewalReceiptMatches(renewal.getRental().getId(),receipts.getFirst(),
                    new LedgerStore.GatewayProof(payment.getId(),payment.getRenewalEvidenceRef(),payment.getGatewayTransactionNo()),obligations.getFirst());
                if(verified)break;
            }
            if(!verified)throw deferred("Verified Payment allocation is missing or changed");
            var source=finance.getIfAvailable();var state=source==null?null:source.read(renewal.getRental()).orElse(null);
            if(source==null||!source.consistentThroughApproval()||state==null||state.checkedAt()==null||state.checkedAt().isAfter(now)
                ||state.dueObligations()==null||!state.dueObligations().isEmpty()||state.unresolvedDispute())
                throw deferred("Shared financial state is unknown or still blocks activation");
        }
        private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
    }
}
