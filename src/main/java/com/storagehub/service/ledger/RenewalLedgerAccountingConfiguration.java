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

/** Shared Staff-attested balance recording and Payment/Ledger proof checks; never initiates gateway transfers. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled",
    "storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class RenewalLedgerAccountingConfiguration {
    @Bean @ConditionalOnMissingBean(RenewalOperationSources.AccountingSource.class)
    RenewalOperationSources.AccountingSource renewalLedgerAccounting(EntityManager em,LedgerStore ledger,
        PaymentRepository payments,PaymentProvenance provenance,ObjectProvider<RenewalSources.FinancialSource> finance,com.storagehub.service.ManualRentalPaymentService manualPayments,ObjectProvider<com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks> localChecks,ObjectProvider<RenewalOverdueFeeService> fees) {
        var source=new Accounting(em,ledger,payments,provenance,finance);source.manualPayments=manualPayments;source.localChecks=localChecks;source.fees=fees.getIfAvailable();return source;
    }

    static class Accounting implements RenewalOperationSources.AccountingSource {
        private final EntityManager em;private final LedgerStore ledger;private final PaymentRepository payments;
        private com.storagehub.service.ManualRentalPaymentService manualPayments;
        private ObjectProvider<com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks> localChecks;
        private RenewalOverdueFeeService fees;
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
            var state=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,renewal.getId());
            if(attempts.isEmpty())return state==null||!state.hasVerifiedPayment();
            return attempts.stream().allMatch(p->p.getStatus()==PaymentStatus.FAILED&&p.getLastReconciledAt()!=null
                ||p.getStatus()==PaymentStatus.PAID&&p.getRenewalLedgerReceiptRef()!=null&&(p.getPurpose()==PaymentType.RENTAL_BALANCE||p.getRenewalSigningPreparedAt()!=null)
                &&Set.of(PaymentProvenance.Classification.VERIFIED,PaymentProvenance.Classification.SANDBOX).contains(provenance.classify(p)));
        }
        @Override public RenewalOperationSources.Statement statement(Renewal renewal,Terms terms,Instant now) {
            var workflow=em.find(RenewalWorkflow.class,renewal.getId());
            var state=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,renewal.getId());
            if(workflow==null||workflow.getAcceptedRevision()==null||state==null||!state.hasVerifiedPayment()
                ||state.getEffectiveSigningDeadline()==null||!state.getEffectiveSigningDeadline().isAfter(now))
                throw deferred("Current paid Renewal signing state required");
            var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
            var snapshot=account(renewal);
            if(specs.size()>1) {
                var initial=obligation(snapshot,workflow,specs.getFirst());
                if(initial.outstanding().signum()!=0||initial.refunded().signum()!=0)throw deferred("Verified unrefunded initial payment required before CASH");
            }
            var spec=specs.getLast();var obligation=obligation(snapshot,workflow,spec);
            if(obligation.refunded().signum()!=0)throw deferred("Refunded Renewal obligation requires reconciliation");
            var fee=fees==null?null:fees.quote(renewal,snapshot,now);
            String version=workflow.getAcceptedRevision().getId()+":"+snapshot.revision()+(fee==null?"":":"+fee.sourceRef());
            UUID ref=UUID.nameUUIDFromBytes((renewal.getId()+":"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var required=new ArrayList<UUID>();required.add(obligation.id());if(fee!=null)fee.posted().forEach(o->required.add(o.id()));
            var expires=fee!=null&&fee.nextChangeAt().isBefore(state.getEffectiveSigningDeadline())?fee.nextChangeAt():state.getEffectiveSigningDeadline();
            return new RenewalOperationSources.Statement(ref,renewal.getId(),version,expires,
                obligation.outstanding().add(fee==null?java.math.BigDecimal.ZERO:fee.outstanding()),"VND",required,
                obligation.outstanding(),fee==null?java.math.BigDecimal.ZERO:fee.outstanding(),fee==null?java.math.BigDecimal.ZERO:fee.unposted());
        }
        @Override public RenewalOperationSources.Receipt cash(Renewal renewal,UUID statement,String receipt,UUID actor,String key,Instant now) {
            throw deferred("CASH receipt evidence file required");
        }
        @Override public RenewalOperationSources.Receipt cash(Renewal renewal,UUID statement,String receipt,UUID file,UUID actor,String key,Instant now) {
            return balance(renewal,statement,receipt,file,"CASH",actor,key,now);
        }
        @Override public RenewalOperationSources.Receipt balance(Renewal renewal,UUID statement,String receipt,UUID file,String method,UUID actor,String key,Instant now) {
            var workflow=em.find(RenewalWorkflow.class,renewal.getId(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if(workflow==null||workflow.getAcceptedRevision()==null||file==null)throw deferred("Accepted revision and CASH evidence required");
            final Terms terms;
            try {terms=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),Terms.class);}
            catch(Exception invalid){throw deferred("Accepted terms are invalid");}
            var payable=statement(renewal,terms,now);
            if(!payable.reference().equals(statement)||payable.amount().signum()<=0)throw deferred("Payable statement is stale or already paid");
            var snapshot=account(renewal);
            // Version is derived from the immutable revision and the exact Ledger revision.
            var allocations=new LinkedHashMap<UUID,java.math.BigDecimal>();
            for(var o:snapshot.obligations())if(payable.requiredObligations().contains(o.id())&&o.outstanding().signum()>0)allocations.put(o.id(),o.outstanding());
            if(payable.unpostedOverdueFee().signum()>0) {
                var fee=fees.quote(renewal,snapshot,now);
                UUID obligationId=ledger.charge(renewal.getRental().getId(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,
                    fee.unposted(),fee.dueAt(),fee.sourceRef(),actor,fee.sourceRef(),snapshot.revision());
                allocations.put(obligationId,fee.unposted());snapshot=account(renewal);
            }
            if(manualPayments==null)throw deferred("Shared manual Payment recorder unavailable");
            var recorded=fees==null?manualPayments.renewal(renewal,workflow.getAcceptedRevision(),em.find(User.class,actor),payable.amount(),method,receipt,key,file,now)
                :manualPayments.renewalSettlement(renewal,workflow.getAcceptedRevision(),em.find(User.class,actor),payable.amount(),method,receipt,key,file,now);
            var payment=recorded.payment();
            LedgerStore.Method receiptMethod="BANK_TRANSFER".equals(method)?LedgerStore.Method.VERIFIED_BANK:LedgerStore.Method.CASH;
            UUID id=ledger.receive(renewal.getRental().getId(),receiptMethod,payable.amount(),
                allocations,"renewal-cash:"+renewal.getId()+":"+receipt,
                file,actor,"renewal-cash:"+renewal.getId()+":"+key,snapshot.revision(),now);
            payment.setRenewalLedgerReceiptRef(id);payment.setRenewalAllocatedAt(now);em.flush();
            return new RenewalOperationSources.Receipt(id,renewal.getId(),statement,payable.amount(),"VND",now,payment.getId(),method);
        }
        private LedgerStore.Snapshot account(Renewal renewal) {
            var snapshot=ledger.read(renewal.getRental().getId()).orElseThrow(()->deferred("Renewal Ledger account is unknown"));
            if(!snapshot.customerId().equals(renewal.getRental().getCustomer().getId())||!snapshot.facilityId().equals(renewal.getRental().getFacility().getId()))
                throw deferred("Ledger resource binding changed");
            return snapshot;
        }
        private LedgerStore.Obligation obligation(LedgerStore.Snapshot snapshot,RenewalWorkflow workflow,FinancialContractSpecifications.Specification spec) {
            var matches=snapshot.obligations().stream().filter(o->Objects.equals(o.renewalId(),spec.renewalId())&&o.kind()==spec.kind()
                &&o.sourceRef().equals(spec.sourceReference())&&o.amount().compareTo(spec.amount())==0
                &&o.dueAt().toEpochMilli()==workflow.getPaymentDeadline().toEpochMilli()).toList();
            if(matches.size()!=1||!ledger.renewalPaymentCommandMatches(matches.getFirst(),spec,snapshot.customerId()))
                throw deferred("Accepted Renewal obligation or writer evidence is missing");
            return matches.getFirst();
        }
        @Override public void requireFullyPaid(Renewal renewal,Terms terms,Instant now) {
            var workflow=em.find(RenewalWorkflow.class,renewal.getId(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if(workflow==null||workflow.getAcceptedRevision()==null||workflow.getPaymentDeadline()==null)
                throw deferred("Accepted Renewal revision and payment deadline are unavailable");
            var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
            var snapshot=account(renewal);
            for(var required:specs) {
                var o=obligation(snapshot,workflow,required);
                if(o.outstanding().signum()!=0||o.refunded().signum()!=0)throw deferred("Renewal obligation is unpaid or refunded");
            }
            var spec=specs.getFirst();var initial=obligation(snapshot,workflow,spec);
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
                    new LedgerStore.GatewayProof(payment.getId(),payment.getRenewalEvidenceRef(),payment.getGatewayTransactionNo()),initial);
                if(verified)break;
            }
            if(!verified)throw deferred("Verified Payment allocation is missing or changed");
            var manualRequired=new LinkedHashMap<UUID,LedgerStore.Obligation>();
            if(specs.size()>1&&specs.getLast().amount().signum()>0) {
                var remainder=obligation(snapshot,workflow,specs.getLast());manualRequired.put(remainder.id(),remainder);
            }
            if(fees!=null) {
                var fee=fees.quote(renewal,snapshot,now);
                if(fee.outstanding().signum()!=0)throw deferred("Daily overdue fee is not fully paid");
                fee.posted().forEach(o->manualRequired.put(o.id(),o));
            }
            var covered=new HashMap<UUID,java.math.BigDecimal>();
            for(var receipt:snapshot.receipts()) {
                var allocations=ledger.renewalManualAllocations(renewal.getRental().getId(),receipt);
                if(allocations.keySet().stream().noneMatch(manualRequired::containsKey))continue;
                var file=em.find(FileAsset.class,receipt.evidenceFileId(),jakarta.persistence.LockModeType.PESSIMISTIC_READ);
                if(file==null||file.getStatus()!=FileAssetStatus.ACTIVE||!"DUONG_RENEWAL_CASH_RECEIPT".equals(file.getEntityType())
                    ||file.getEntityId()==null)throw deferred("CASH receipt evidence is unavailable");
                var related=em.find(Renewal.class,file.getEntityId());
                if(related==null||!related.getRental().getId().equals(renewal.getRental().getId()))throw deferred("Manual receipt belongs to another rental");
                var relatedWorkflow=em.find(RenewalWorkflow.class,related.getId());
                if(relatedWorkflow==null||relatedWorkflow.getAcceptedRevision()==null)throw deferred("Manual receipt accepted revision missing");
                if(!manualRequired.keySet().containsAll(allocations.keySet())) {
                    final Terms relatedTerms;
                    try{relatedTerms=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(relatedWorkflow.getAcceptedRevision().getQuote().getTermsJson(),Terms.class);}
                    catch(Exception invalid){throw deferred("Prior receipt accepted terms invalid");}
                    var relatedSpecs=FinancialContractSpecifications.accepted(relatedWorkflow.getAcceptedRevision(),relatedTerms);
                    for(var id:allocations.keySet())if(!manualRequired.containsKey(id)) {
                        var extra=snapshot.obligations().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow(()->deferred("Manual allocation missing"));
                        if(relatedSpecs.stream().noneMatch(s->s.kind()==LedgerStore.Kind.RENEWAL_REMAINDER&&s.sourceReference().equals(extra.sourceRef())
                            &&s.amount().compareTo(extra.amount())==0))throw deferred("Manual receipt includes unrelated obligations");
                    }
                }
                var events=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind='CASH'",com.storagehub.service.renewal.operations.RenewalOperationEvent.class)
                    .setParameter("id",related.getId()).getResultList();
                boolean bound=false;
                for(var event:events)try {
                    var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getPayloadJson());
                    bound|=receipt.id().toString().equals(json.path("reference").asText())
                        &&file.getUploadedBy()!=null&&Objects.equals(event.getActorId(),file.getUploadedBy().getId())
                        &&!event.getOccurredAt().isBefore(receipt.receivedAt())&&!event.getOccurredAt().isAfter(now);
                }catch(Exception invalid){throw deferred("CASH receipt event is invalid");}
                if(!bound)throw deferred("Authorized remaining-payment receipt event required");
                boolean paymentBound=payments.findAllByRenewal_IdAndStatusIn(related.getId(),List.of(PaymentStatus.PAID)).stream().anyMatch(p->
                    p.getPurpose()==PaymentType.RENTAL_BALANCE&&p.getRenewalRevision()!=null&&p.getRenewalRevision().getId().equals(relatedWorkflow.getAcceptedRevision().getId())
                    &&receipt.id().equals(p.getRenewalLedgerReceiptRef())&&p.getAmount().compareTo(receipt.amount())==0
                    &&p.getPaidAt()!=null&&p.getPaidAt().toEpochMilli()==receipt.receivedAt().toEpochMilli()
                    &&("renewal-cash:"+related.getId()+":"+p.getGatewayTransactionNo()).equals(receipt.evidenceRef())
                    &&Objects.equals(p.getInitiatedBy().getId(),file.getUploadedBy().getId())
                    &&(receipt.method()==LedgerStore.Method.CASH?"CASH":"BANK_TRANSFER").equals(p.getGatewayBankCode())
                    &&provenance.manualReceiptMatches(p,receipt.evidenceFileId()));
                if(!paymentBound)throw deferred("Shared remaining-rent Payment proof required");
                allocations.forEach((id,amount)->covered.merge(id,amount,java.math.BigDecimal::add));
            }
            for(var required:manualRequired.values())if(required.refunded().signum()!=0||required.outstanding().signum()!=0
                ||covered.getOrDefault(required.id(),java.math.BigDecimal.ZERO).compareTo(required.amount())!=0)
                throw deferred("Verified remaining-rent and overdue-fee allocations required");
            var local=localChecks==null?null:localChecks.getIfAvailable();
            if(local!=null){local.requireKnownFinance(renewal.getRental(),renewal.getId(),now);return;}
            var source=finance.getIfAvailable();var state=source==null?null:source.read(renewal.getRental()).orElse(null);
            if(source==null||!source.consistentThroughApproval()||state==null||state.checkedAt()==null||state.checkedAt().isAfter(now)
                ||state.dueObligations()==null||!state.dueObligations().isEmpty()||state.unresolvedDispute())
                throw deferred("Shared financial state is unknown or still blocks activation");
        }
        private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
    }
}
