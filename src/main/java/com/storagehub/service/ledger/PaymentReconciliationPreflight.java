package com.storagehub.service.ledger;

import com.storagehub.domain.model.*;
import com.storagehub.service.PaymentProvenance;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Deterministic read-only plan. It never converts a Payment ID into a FileAsset or posts money. */
@Service @RequiredArgsConstructor
public class PaymentReconciliationPreflight {
    public record Plan(String status,String sourceReference,UUID obligationId,String retryFingerprint) {}
    private final ObjectProvider<LedgerStore> ledgers;
    public Plan read(Payment p,Rental r,ReservationPricingSnapshot snapshot,PaymentProvenance.Classification classification) {
        String ref=p==null||p.getId()==null?null:"payment:"+p.getId();
        if(p==null||p.getId()==null||p.getReservation()==null||p.getReservation().getId()==null
            ||p.getReservation().getCustomer()==null||p.getReservation().getCustomer().getId()==null
            ||p.getReservation().getFacility()==null||p.getReservation().getFacility().getId()==null)
            return plan("SOURCE_IDENTITY_UNKNOWN",ref,null,null);
        if(r==null)return plan("WAITING_RENTAL_ACTIVATION",ref,null,null);
        if(r.getId()==null||r.getReservation()==null||r.getReservation().getId()==null
            ||r.getReservation().getCustomer()==null||r.getReservation().getCustomer().getId()==null
            ||r.getReservation().getFacility()==null||r.getReservation().getFacility().getId()==null
            ||r.getCustomer()==null||r.getCustomer().getId()==null||r.getFacility()==null||r.getFacility().getId()==null)
            return plan("SOURCE_IDENTITY_UNKNOWN",ref,null,null);
        if(!Objects.equals(p.getReservation().getId(),r.getReservation().getId())
            ||!Objects.equals(p.getReservation().getCustomer().getId(),r.getCustomer().getId())
            ||!Objects.equals(p.getReservation().getFacility().getId(),r.getFacility().getId())
            ||!Objects.equals(r.getReservation().getCustomer().getId(),r.getCustomer().getId())
            ||!Objects.equals(r.getReservation().getFacility().getId(),r.getFacility().getId()))
            return plan("CROSS_RENTAL_REJECTED",ref,null,null);
        String fingerprint=ref+":"+r.getId()+":"+r.getCustomer().getId()+":"+p.getPurpose()+":"+(p.getAmount()==null?"missing":p.getAmount().stripTrailingZeros().toPlainString());
        if(classification!=PaymentProvenance.Classification.VERIFIED)return plan("NON_VERIFIED_MONEY",ref,null,fingerprint);
        if(p.getPurpose()!=PaymentType.RESERVATION_DEPOSIT || snapshot==null)return plan("ALLOCATION_PURPOSE_UNRESOLVED",ref,null,fingerprint);
        if(snapshot.getId()==null||snapshot.getReservation()==null
            ||!Objects.equals(snapshot.getReservation().getId(),r.getReservation().getId()))
            return plan("CONTRACT_SOURCE_MISMATCH",ref,null,fingerprint);
        var spec=FinancialContractSpecifications.initial(r,snapshot).getFirst();
        if(p.getAmount()==null || p.getAmount().signum()<=0 || p.getAmount().compareTo(snapshot.getReservationDepositAmount())!=0)
            return plan("CONTRACT_AMOUNT_MISMATCH",ref,null,fingerprint);
        var ledger=ledgers.getIfAvailable();var state=ledger==null?Optional.<LedgerStore.Snapshot>empty():ledger.read(r.getId());
        if(ledger!=null){var existing=ledger.receiptEvidenceRental(ref);if(existing.isPresent())return plan(existing.get().equals(r.getId())?"PAYMENT_ALREADY_REFERENCED":"CROSS_RENTAL_REJECTED",ref,null,fingerprint);}
        if(state.isEmpty())return plan("LEDGER_ACCOUNT_MISSING",ref,null,fingerprint);
        var s=state.get();
        if(!Objects.equals(s.customerId(),r.getCustomer().getId())||!Objects.equals(s.facilityId(),r.getFacility().getId()))return plan("CROSS_RENTAL_REJECTED",ref,null,fingerprint);
        if(s.receipts().stream().anyMatch(v->ref.equals(v.evidenceRef())))return plan("PAYMENT_ALREADY_REFERENCED",ref,null,fingerprint);
        var targets=s.obligations().stream().filter(o->o.kind()==LedgerStore.Kind.RENT && o.renewalId()==null && spec.sourceReference().equals(o.sourceRef())).toList();
        if(targets.size()!=1)return plan("CONTRACT_OBLIGATION_MISSING_OR_AMBIGUOUS",ref,null,fingerprint);
        var target=targets.getFirst();
        if(p.getAmount().compareTo(target.outstanding())>0)return plan("ALLOCATION_EXCEEDS_OUTSTANDING",ref,target.id(),fingerprint);
        // Mapping is unambiguous; the remaining blocker is the existing FileAsset evidence interface.
        return plan("FIN_03A_EVIDENCE_MODEL_REQUIRED",ref,target.id(),fingerprint);
    }
    private static Plan plan(String status,String ref,UUID obligation,String fingerprint){return new Plan(status,ref,obligation,fingerprint);}
}
