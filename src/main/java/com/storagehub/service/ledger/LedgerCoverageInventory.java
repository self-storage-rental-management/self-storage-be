package com.storagehub.service.ledger;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.PaymentProvenance;
import com.storagehub.service.renewal.persistence.RenewalAcceptedRevision;
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
    public record PaymentEvidence(UUID paymentId,String provenance,boolean linkedReceipt,boolean refundUnreconciled) {}
    public record Inventory(UUID rentalId,Long ledgerRevision,String completeness,List<String> gaps,
        List<PaymentEvidence> payments,boolean consistentThroughApproval) {}
    private final ReservationPricingSnapshotRepository pricing;
    private final PaymentRepository payments;
    private final PaymentProvenance provenance;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public Inventory inspect(Rental rental,Optional<LedgerStore.Snapshot> stored) {
        var gaps=new LinkedHashSet<String>();var evidence=new ArrayList<PaymentEvidence>();
        var snapshot=stored.orElse(null);
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
                boolean linked=snapshot!=null&&snapshot.receipts().stream().anyMatch(p->("payment:"+payment.getId()).equals(p.evidenceRef()));
                if(payment.getStatus()==PaymentStatus.PAID){
                    if(classification!=PaymentProvenance.Classification.VERIFIED)gaps.add("PAYMENT_NOT_PRODUCTION_VERIFIED");
                    else if(!linked)gaps.add("VERIFIED_PAYMENT_NOT_RECONCILED");
                }
                boolean refund=payment.getRefundedAmount()!=null&&payment.getRefundedAmount().signum()>0;
                if(refund)gaps.add("PAYMENT_REFUND_EXECUTION_UNRECONCILED");
                evidence.add(new PaymentEvidence(payment.getId(),classification.name(),linked,refund));
            }
        }
        var revisions=em.createQuery("select a from RenewalAcceptedRevision a where a.renewal.rental.id=:id",RenewalAcceptedRevision.class)
            .setParameter("id",rental.getId()).getResultList();
        var acceptedSpecifications=new ArrayList<FinancialContractSpecifications.Specification>();
        for(var revision:revisions)try{
            var terms=mapper.readValue(revision.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
            // Accepted terms are inventory candidates, not proof that the obligation is effective.
            acceptedSpecifications.addAll(FinancialContractSpecifications.accepted(revision,terms));
            gaps.add("RENEWAL_EFFECTIVE_EVENT_UNCERTIFIED");
        }catch(Exception ex){gaps.add("RENEWAL_ACCEPTED_SOURCE_INVALID");}
        for(var spec:specifications)if(snapshot==null||snapshot.obligations().stream().filter(o->
            o.kind()==spec.kind()&&Objects.equals(o.renewalId(),spec.renewalId())&&spec.sourceReference().equals(o.sourceRef())
                &&o.amount().compareTo(spec.amount())==0).count()!=1)gaps.add("INITIAL_OBLIGATION_UNPOSTED_OR_INVALID:"+spec.kind());
        if(snapshot!=null){
            var allowed=new ArrayList<>(specifications);allowed.addAll(acceptedSpecifications);
            if(snapshot.obligations().stream().anyMatch(o->allowed.stream().noneMatch(s->s.kind()==o.kind()
                &&Objects.equals(s.renewalId(),o.renewalId())&&s.sourceReference().equals(o.sourceRef())
                &&s.amount().compareTo(o.amount())==0)))gaps.add("OBLIGATION_SOURCE_UNVERIFIED");
            if(snapshot.receipts().stream().anyMatch(p->p.method()!=LedgerStore.Method.SIMULATED))gaps.add("RECEIPT_WRITER_PROVENANCE_UNCERTIFIED");
            if(snapshot.refunds().stream().anyMatch(f->!"EXECUTED".equals(f.status())||f.payoutRef()==null||f.payoutRef().isBlank()))gaps.add("LEDGER_REFUND_INCOMPLETE");
        }
        gaps.add("SECURITY_DEPOSIT_HELD_SOURCE_UNCERTIFIED");gaps.add("HISTORICAL_WRITER_COVERAGE_UNCERTIFIED");
        return new Inventory(rental.getId(),snapshot==null?null:snapshot.revision(),snapshot==null?"UNKNOWN":"PARTIAL",
            List.copyOf(gaps),List.copyOf(evidence),false);
    }
}
