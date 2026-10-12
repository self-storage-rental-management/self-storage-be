package com.storagehub.service.ledger;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.persistence.RenewalAcceptedRevision;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only inventory. Diagnostics never certify writer consistency or create missing rows. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerCoverageInventory {
    public record PaymentEvidence(UUID paymentId,String provenance,boolean linkedReceipt,boolean refundUnreconciled,boolean eligibleForReconciliation) {}
    public record RenewalDepositEvidence(UUID renewalId,UUID acceptedRevisionId,String status,UUID obligationId) {}
    public record Inventory(UUID rentalId,Long ledgerRevision,String completeness,List<String> gaps,
        List<PaymentEvidence> payments,boolean consistentThroughApproval,List<RenewalDepositEvidence> renewalDeposits) {}
    private final ReservationPricingSnapshotRepository pricing;
    private final PaymentRepository payments;
    private final PaymentProvenance provenance;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final SandboxPaymentRecognitionPolicy sandboxPolicy;
    private final LedgerStore ledger;
    private final ActivityLogRepository logs;

    public Inventory inspect(Rental rental,Optional<LedgerStore.Snapshot> stored) {
        var gaps=new LinkedHashSet<String>();var evidence=new ArrayList<PaymentEvidence>();
        var snapshot=stored.orElse(null);
        var validatedReceipts=new HashSet<UUID>();
        if(snapshot==null)gaps.add("LEDGER_ACCOUNT_MISSING");
        else if(!Objects.equals(snapshot.rentalId(),rental.getId())||snapshot.revision()<0
            ||rental.getCustomer()==null||rental.getFacility()==null
            ||!Objects.equals(snapshot.customerId(),rental.getCustomer().getId())
            ||!Objects.equals(snapshot.facilityId(),rental.getFacility().getId()))gaps.add("LEDGER_BINDING_INVALID");
        var specifications=new ArrayList<FinancialContractSpecifications.Specification>();
        if(rental.getReservation()==null)gaps.add("BOOKING_SOURCE_MISSING");
        else {
            var contract=pricing.findByReservation_Id(rental.getReservation().getId());
            if(contract.isEmpty())gaps.add("BOOKING_SNAPSHOT_MISSING");
            else try{specifications.addAll(FinancialContractSpecifications.initial(rental,contract.get()));}
                catch(com.storagehub.common.api.ApiException ex){gaps.add("BOOKING_SNAPSHOT_INVALID");}
            var history=payments.findAllByReservation_IdAndStatusIn(rental.getReservation().getId(),List.of(PaymentStatus.values()));
            if(history.isEmpty())gaps.add("BOOKING_PAYMENT_EVIDENCE_MISSING");
            for(var payment:history){
                boolean bound=payment.getReservation()!=null&&payment.getReservation().getCustomer()!=null
                    &&rental.getCustomer()!=null&&Objects.equals(payment.getReservation().getId(),rental.getReservation().getId())
                    &&Objects.equals(payment.getReservation().getCustomer().getId(),rental.getCustomer().getId())
                    &&payment.getReservation().getFacility()!=null&&rental.getFacility()!=null
                    &&Objects.equals(payment.getReservation().getFacility().getId(),rental.getFacility().getId());
                var classification=bound?provenance.classify(payment):PaymentProvenance.Classification.UNVERIFIED;
                if(!bound)gaps.add("PAYMENT_BINDING_INVALID");
                if(classification==PaymentProvenance.Classification.DISPUTED)gaps.add("PAYMENT_DISPUTED");
                boolean eligible=bound&&sandboxPolicy.evaluate(payment,classification).eligibleForReconciliation();
                boolean linked=false;
                if(snapshot!=null&&eligible){
                    var rentSpecs=specifications.stream().filter(s->s.kind()==LedgerStore.Kind.RENT).toList();
                    var candidates=snapshot.receipts().stream().filter(p->p.method()==LedgerStore.Method.VNPAY_SANDBOX
                        &&("payment:"+payment.getId()).equals(p.evidenceRef())).toList();
                    if(rentSpecs.size()==1&&candidates.size()==1&&snapshot.obligations().stream().filter(o->
                        o.kind()==LedgerStore.Kind.RENT&&o.renewalId()==null&&rentSpecs.getFirst().sourceReference().equals(o.sourceRef())
                        &&o.amount().compareTo(rentSpecs.getFirst().amount())==0).count()==1){
                        var receipt=candidates.getFirst();
                        var proofs=logs.findTop2ByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc("Payment",payment.getId(),PaymentProvenance.CALLBACK_ACTION);
                        linked=proofs.size()==1&&payment.getAmount()!=null&&payment.getPaidAt()!=null
                            &&receipt.amount().compareTo(payment.getAmount())==0&&receipt.evidenceFileId()==null
                            &&receipt.receivedAt().toEpochMilli()==payment.getPaidAt().toEpochMilli()
                            &&ledger.sandboxReceiptMatches(rental.getId(),receipt,new LedgerStore.GatewayProof(payment.getId(),proofs.getFirst().getId(),payment.getGatewayTransactionNo()),rentSpecs.getFirst().sourceReference());
                        if(linked)validatedReceipts.add(receipt.id());
                    }
                }
                if(payment.getStatus()==PaymentStatus.PAID){
                    if(!eligible)gaps.add("PAYMENT_NOT_PROJECT_SANDBOX");
                    else if(!linked)gaps.add("SANDBOX_PAYMENT_NOT_RECONCILED");
                }
                boolean refund=payment.getRefundedAmount()!=null&&payment.getRefundedAmount().signum()>0;
                if(refund)gaps.add("PAYMENT_REFUND_EXECUTION_UNRECONCILED");
                evidence.add(new PaymentEvidence(payment.getId(),classification.name(),linked,refund,eligible));
            }
        }
        var revisions=em.createQuery("select a from RenewalAcceptedRevision a where a.renewal.rental.id=:id",RenewalAcceptedRevision.class)
            .setParameter("id",rental.getId()).getResultList();
        var acceptedSpecifications=new ArrayList<FinancialContractSpecifications.Specification>();
        var deposits=new ArrayList<RenewalDepositEvidence>();
        for(var revision:revisions)try{
            var workflow=em.find(RenewalWorkflow.class,revision.getRenewal().getId());
            if(workflow!=null&&workflow.getAcceptedRevision()!=null&&!revision.getId().equals(workflow.getAcceptedRevision().getId()))continue;
            var terms=mapper.readValue(revision.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
            var spec=FinancialContractSpecifications.accepted(revision,terms).getFirst();
            if (terms.renewalDepositAmount() != null && terms.renewalDepositAmount().signum() == 0) {
                if(workflow!=null&&workflow.getPaymentDeadline()!=null&&workflow.getExtensionHoldRef()!=null) {
                    var matches=snapshot==null?List.<LedgerStore.Obligation>of():snapshot.obligations().stream().filter(o->
                        o.kind()==spec.kind()&&Objects.equals(o.renewalId(),spec.renewalId())&&spec.sourceReference().equals(o.sourceRef())
                        &&o.amount().compareTo(spec.amount())==0&&o.dueAt().toEpochMilli()==workflow.getPaymentDeadline().toEpochMilli()).toList();
                    if(matches.size()!=1||!ledger.renewalFullRentCommandMatches(matches.getFirst(),spec,rental.getCustomer().getId()))
                        gaps.add("RENEWAL_FULL_RENT_OBLIGATION_UNPOSTED_OR_INVALID");
                    else {
                        acceptedSpecifications.add(spec);
                        for(var payment:payments.findAllByRenewal_IdOrderByCreatedAtAsc(spec.renewalId())) {
                            if(payment.getRenewalRevision()==null||!revision.getId().equals(payment.getRenewalRevision().getId()))continue;
                            boolean eligible=sandboxPolicy.evaluate(payment,provenance.classify(payment)).eligibleForReconciliation();
                            var candidates=snapshot.receipts().stream().filter(p->("payment:"+payment.getId()).equals(p.evidenceRef())).toList();
                            boolean linked=eligible&&payment.getRenewalEvidenceRef()!=null&&candidates.size()==1
                                &&candidates.getFirst().method()==LedgerStore.Method.VNPAY_SANDBOX
                                &&candidates.getFirst().amount().compareTo(payment.getAmount())==0
                                &&candidates.getFirst().receivedAt().toEpochMilli()==payment.getPaidAt().toEpochMilli()
                                &&ledger.renewalReceiptMatches(rental.getId(),candidates.getFirst(),new LedgerStore.GatewayProof(
                                    payment.getId(),payment.getRenewalEvidenceRef(),payment.getGatewayTransactionNo()),matches.getFirst());
                            if(linked)validatedReceipts.add(candidates.getFirst().id());
                            if(payment.getStatus()==PaymentStatus.PAID&&!linked)gaps.add("SANDBOX_PAYMENT_NOT_RECONCILED");
                            evidence.add(new PaymentEvidence(payment.getId(),provenance.classify(payment).name(),linked,
                                payment.getRefundedAmount()!=null&&payment.getRefundedAmount().signum()>0,eligible));
                        }
                    }
                }
                continue;
            }
            if(workflow!=null&&Set.of(RenewalStatus.pending,RenewalStatus.accepted,RenewalStatus.rejected).contains(workflow.getRenewal().getStatus())
                &&workflow.getPaymentDeadline()==null&&workflow.getExtensionHoldRef()==null){
                deposits.add(new RenewalDepositEvidence(spec.renewalId(),revision.getId(),"NOT_EFFECTIVE",null));continue;
            }
            boolean approved=workflow!=null&&workflow.getRenewal()!=null&&workflow.getAcceptedRevision()!=null
                &&spec.rentalId().equals(rental.getId())&&spec.renewalId().equals(workflow.getRenewal().getId())
                &&revision.getId().equals(workflow.getAcceptedRevision().getId())&&workflow.getReviewer()!=null
                &&workflow.getReviewedAt()!=null&&workflow.getPaymentDeadline()!=null&&workflow.getExtensionHoldRef()!=null
                &&!workflow.getReviewedAt().isBefore(revision.getAcceptedAt())&&workflow.getPaymentDeadline().isAfter(workflow.getReviewedAt())
                &&workflow.getRenewal().getStatus()!=RenewalStatus.pending&&workflow.getRenewal().getStatus()!=RenewalStatus.rejected;
            var matches=snapshot==null?List.<LedgerStore.Obligation>of():snapshot.obligations().stream().filter(o->
                o.kind()==spec.kind()&&Objects.equals(o.renewalId(),spec.renewalId())&&spec.sourceReference().equals(o.sourceRef())
                &&o.amount().compareTo(spec.amount())==0&&approved&&o.dueAt().toEpochMilli()==workflow.getPaymentDeadline().toEpochMilli()).toList();
            boolean matched=approved&&matches.size()==1&&ledger.renewalDepositCommandMatches(matches.getFirst(),spec,workflow.getReviewer().getId());
            deposits.add(new RenewalDepositEvidence(spec.renewalId(),revision.getId(),matched?"MATCHED":approved?"UNPOSTED_OR_INVALID":"APPROVAL_UNCERTIFIED",matched?matches.getFirst().id():null));
            if(matched)acceptedSpecifications.add(spec);
            else gaps.add(approved?"RENEWAL_DEPOSIT_UNPOSTED_OR_INVALID":"RENEWAL_APPROVAL_UNCERTIFIED");
            if(approved){
                gaps.add("RENEWAL_REMAINDER_EFFECTIVE_EVENT_UNCERTIFIED");
                if(Set.of(RenewalStatus.cancelled,RenewalStatus.payment_expired,RenewalStatus.payment_failed).contains(workflow.getRenewal().getStatus()))gaps.add("RENEWAL_TERMINAL_ACCOUNTING_UNRECONCILED");
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException|com.storagehub.common.api.ApiException ex){gaps.add("RENEWAL_ACCEPTED_SOURCE_INVALID");}
        for(var spec:specifications)if(snapshot==null||snapshot.obligations().stream().filter(o->
            o.kind()==spec.kind()&&Objects.equals(o.renewalId(),spec.renewalId())&&spec.sourceReference().equals(o.sourceRef())
                &&o.amount().compareTo(spec.amount())==0).count()!=1)gaps.add("INITIAL_OBLIGATION_UNPOSTED_OR_INVALID:"+spec.kind());
        if(snapshot!=null){
            var allowed=new ArrayList<>(specifications);allowed.addAll(acceptedSpecifications);
            if(snapshot.obligations().stream().anyMatch(o->allowed.stream().noneMatch(s->s.kind()==o.kind()
                &&Objects.equals(s.renewalId(),o.renewalId())&&s.sourceReference().equals(o.sourceRef())
                &&s.amount().compareTo(o.amount())==0)))gaps.add("OBLIGATION_SOURCE_UNVERIFIED");
            if(snapshot.receipts().stream().anyMatch(p->p.method()!=LedgerStore.Method.SIMULATED&&p.method()!=LedgerStore.Method.VNPAY_SANDBOX))gaps.add("RECEIPT_WRITER_PROVENANCE_UNCERTIFIED");
            if(snapshot.receipts().stream().anyMatch(p->p.method()==LedgerStore.Method.VNPAY_SANDBOX&&!validatedReceipts.contains(p.id())))gaps.add("SANDBOX_RECEIPT_BINDING_INVALID");
            if(snapshot.refunds().stream().anyMatch(f->!"EXECUTED".equals(f.status())||f.payoutRef()==null||f.payoutRef().isBlank()))gaps.add("LEDGER_REFUND_INCOMPLETE");
        }
        gaps.add("SECURITY_DEPOSIT_HELD_SOURCE_UNCERTIFIED");gaps.add("HISTORICAL_WRITER_COVERAGE_UNCERTIFIED");
        return new Inventory(rental.getId(),snapshot==null?null:snapshot.revision(),snapshot==null?"UNKNOWN":"PARTIAL",
            List.copyOf(gaps),List.copyOf(evidence),false,List.copyOf(deposits));
    }
}
