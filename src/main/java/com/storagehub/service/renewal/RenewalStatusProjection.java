package com.storagehub.service.renewal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.ledger.*;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RenewalStatusProjection {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private final EntityManager em;
    private final PaymentRepository payments;
    private final RenewalTransactionReader transactionReader;
    private final ObjectProvider<LedgerStore> ledgers;
    private final ObjectProvider<RenewalOverdueFeeService> fees;
    private final ObjectMapper mapper;
    private final TransactionTemplate read;
    public RenewalStatusProjection(EntityManager em,PaymentRepository payments,RenewalTransactionReader transactionReader,
        ObjectProvider<LedgerStore> ledgers,ObjectProvider<RenewalOverdueFeeService> fees,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.em=em;this.payments=payments;this.transactionReader=transactionReader;this.ledgers=ledgers;this.fees=fees;this.mapper=mapper;
        read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record Financial(RenewalStatusSummary.Invoice invoice,List<RenewalStatusSummary.Transaction> transactions) {}
    public RenewalStatusSummary project(UUID id,Instant now) {
        try {return read.execute(status->complete(id,now,false));}
        catch(ApiException|org.springframework.dao.DataAccessException unavailable) {
            // The optional financial probe rolls back independently, before the conservative read.
            return read.execute(status->complete(id,now,true));
        }
    }
    public static RenewalStatusSummary applyVerification(RenewalStatusSummary summary,RenewalResponse.SystemVerification verification,String reviewState) {
        if(Set.of("UNDER_REVIEW","AWAITING_PAYMENT").contains(summary.workflowStatus())
            &&("AWAITING_CUSTOMER_CONFIRMATION".equals(reviewState)||verification!=null&&"BLOCKED".equals(verification.status())))
            return new RenewalStatusSummary("ON_HOLD",summary.invoice(),summary.transactions(),summary.checkedAt());
        return summary;
    }

    public RenewalStatusSummary project(Renewal n,Instant now) {
        return project(n.getId(),now);
    }
    private RenewalStatusSummary complete(UUID id,Instant now,boolean unavailable) {
        var n=em.find(Renewal.class,id);
        if(n==null)throw ApiExceptions.notFound("Renewal was not found");
        Financial financial;
        if(unavailable) {
            var attempts=payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).stream()
                .filter(p->p.getPurpose()==PaymentType.RENEWAL||p.getPurpose()==PaymentType.RENTAL_BALANCE).toList();
            financial=new Financial(RenewalStatusSummary.Invoice.unknown("UNKNOWN","FINANCIAL_RECONCILIATION_REQUIRED"),
                attempts.stream().map(p->transaction(p,false,ZERO,now)).toList());
        }else financial=financial(id,now);
        var state=em.find(RenewalOperationState.class,n.getId());
        var workflow=em.find(RenewalWorkflow.class,n.getId());
        boolean preparationBlocked=n.getStatus()==RenewalStatus.accepted
            &&payments.findAllByRenewal_IdOrderByCreatedAtAsc(id).stream().anyMatch(RenewalPreparationResolution::blocked);
        return new RenewalStatusSummary(preparationBlocked?"ON_HOLD":workflow(n,state,workflow,financial.transactions(),now),financial.invoice(),financial.transactions(),now);
    }
    private Financial financial(UUID id,Instant now) {
        var n=em.find(Renewal.class,id);
        var workflow=em.find(RenewalWorkflow.class,id);
        var attempts=payments.findAllByRenewal_IdOrderByCreatedAtAsc(id).stream()
            .filter(p->p.getPurpose()==PaymentType.RENEWAL||p.getPurpose()==PaymentType.RENTAL_BALANCE).toList();
        var ledger=ledgers.getIfAvailable();
        var snapshot=ledger==null?Optional.<LedgerStore.Snapshot>empty():ledger.read(n.getRental().getId());
        var receipts=new HashSet<UUID>();
        var transactions=new ArrayList<RenewalStatusSummary.Transaction>();
        var boundReceiptIds=new HashSet<UUID>();
        for(var p:attempts) {
            var receipt=snapshot.stream().flatMap(s->s.receipts().stream())
                .filter(r->r.id().equals(p.getRenewalLedgerReceiptRef())).findFirst().orElse(null);
            boolean bound=transactionReader.verifiedAllocation(p,snapshot)
                &&p.getRenewalRevision()!=null&&workflow!=null&&workflow.getAcceptedRevision()!=null
                &&p.getRenewalRevision().getId().equals(workflow.getAcceptedRevision().getId());
            if(bound&&!boundReceiptIds.add(receipt.id()))throw ApiExceptions.conflict("Duplicate renewal receipt binding");
            if(bound)receipts.add(receipt.id());
            transactions.add(transactionReader.project(p,snapshot,now));
        }
        if(workflow==null||workflow.getAcceptedRevision()==null)
            return new Financial(RenewalStatusSummary.Invoice.unknown("UNKNOWN","ACCEPTED_TERMS_UNAVAILABLE"),transactions);
        if(snapshot.isEmpty())return new Financial(RenewalStatusSummary.Invoice.unknown(ledger!=null&&workflow.getPaymentDeadline()==null?"NOT_ISSUED":"UNKNOWN","NO_POSTED_OBLIGATIONS"),transactions);
        var account=snapshot.get();
        if(!account.customerId().equals(n.getRental().getCustomer().getId())||!account.facilityId().equals(n.getRental().getFacility().getId()))
            throw ApiExceptions.conflict("Renewal ledger account binding changed");
        final RenewalQuoteResponse.Terms terms;
        try {terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Accepted renewal terms unavailable");}
        if(terms.monthly())return new Financial(RenewalStatusSummary.Invoice.unknown("UNKNOWN","UNSUPPORTED_PAYMENT_PLAN"),transactions);
        var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
        var obligations=new ArrayList<>(account.obligations().stream().filter(o->id.equals(o.renewalId())).toList());
        if(obligations.isEmpty())return new Financial(RenewalStatusSummary.Invoice.unknown(workflow.getPaymentDeadline()==null?"NOT_ISSUED":"UNKNOWN","NO_POSTED_OBLIGATIONS"),transactions);
        for(var spec:specs)if(obligations.stream().filter(o->o.kind()==spec.kind()&&o.sourceRef().equals(spec.sourceReference())
            &&o.amount().compareTo(spec.amount())==0).count()!=1)throw ApiExceptions.conflict("Renewal invoice coverage incomplete");
        for(var o:obligations)if(specs.stream().noneMatch(s->s.sourceReference().equals(o.sourceRef()))
            &&!(o.kind()==LedgerStore.Kind.RENEWAL_REMAINDER&&o.sourceRef().startsWith("rental-overdue:"+n.getRental().getId()+":")))
            throw ApiExceptions.conflict("Unexpected renewal invoice obligation");
        BigDecimal unposted=ZERO;
        var state=em.find(RenewalOperationState.class,id);
        if(state!=null&&state.getPhase()==RenewalOperationState.Phase.SIGNING) {
            var source=fees.getIfAvailable();
            if(source==null)throw ApiExceptions.conflict("Renewal overdue fee coverage unavailable");
            var fee=source.quote(n,account,now);unposted=fee.unposted();
            // Signing can collect rental-level overdue fees posted by a previous renewal.
            // Use the same fee source as the payable statement; do not silently omit these debts.
            for(var posted:fee.posted())if(obligations.stream().noneMatch(o->o.id().equals(posted.id())))obligations.add(posted);
            for(var relatedId:fee.posted().stream().map(LedgerStore.Obligation::renewalId).filter(Objects::nonNull).filter(other->!other.equals(id)).distinct().toList())
                for(var related:payments.findAllByRenewal_IdOrderByCreatedAtAsc(relatedId))
                    if(transactionReader.verifiedAllocation(related,snapshot))receipts.add(related.getRenewalLedgerReceiptRef());
        }
        var allocations=ledger.renewalAllocations(account.rentalId(),id);
        var invoice=invoice(terms,obligations,allocations,receipts,account.refunds(),unposted,now,
            Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.payment_expired).contains(n.getStatus()));
        return new Financial(invoice,List.copyOf(transactions));
    }
    static RenewalStatusSummary.Invoice invoice(RenewalQuoteResponse.Terms terms,List<LedgerStore.Obligation> obligations,
        List<LedgerStore.Allocation> allocations,Set<UUID> verifiedReceipts,List<LedgerStore.Refund> refunds,
        BigDecimal unposted,Instant now,boolean terminal) {
        BigDecimal original=unposted,credit=ZERO,paid=ZERO,refunded=ZERO,outstanding=unposted,due=unposted,overdue=unposted;
        var ids=new HashSet<UUID>();
        for(var o:obligations) {
            ids.add(o.id());
            var confirmedRefund=refunds.stream().filter(r->r.obligationId().equals(o.id())&&"EXECUTED".equals(r.status())
                &&r.payoutRef()!=null&&!r.payoutRef().isBlank()).map(LedgerStore.Refund::amount).reduce(ZERO,BigDecimal::add);
            if(confirmedRefund.compareTo(o.refunded())!=0)return RenewalStatusSummary.Invoice.unknown("UNKNOWN","UNVERIFIED_REFUNDS");
            var verified=allocations.stream().filter(a->a.obligationId().equals(o.id())&&verifiedReceipts.contains(a.receiptId()))
                .map(LedgerStore.Allocation::amount).reduce(ZERO,BigDecimal::add);
            if(verified.compareTo(o.allocated())!=0)return RenewalStatusSummary.Invoice.unknown("UNKNOWN","UNVERIFIED_ALLOCATIONS");
            BigDecimal adjustment=o.amount().subtract(o.allocated()).add(o.refunded()).subtract(o.outstanding());
            if(adjustment.signum()<0||o.allocated().compareTo(o.refunded())<0)return RenewalStatusSummary.Invoice.unknown("UNKNOWN","INCONSISTENT_LEDGER");
            original=original.add(o.amount());credit=credit.add(adjustment);paid=paid.add(verified.subtract(o.refunded()));
            refunded=refunded.add(o.refunded());outstanding=outstanding.add(o.outstanding());
            if(!o.dueAt().isAfter(now))due=due.add(o.outstanding());
            if(o.dueAt().isBefore(now))overdue=overdue.add(o.outstanding());
        }
        var total=original.subtract(credit);
        if(total.subtract(paid).compareTo(outstanding)!=0)return RenewalStatusSummary.Invoice.unknown("UNKNOWN","INCONSISTENT_LEDGER");
        String status=terminal&&outstanding.signum()==0?"CANCELLED":outstanding.signum()==0?"PAID":paid.signum()>0?"PARTIALLY_PAID":"UNPAID";
        boolean pending=refunds.stream().anyMatch(r->ids.contains(r.obligationId())&&"RESERVED".equals(r.status()));
        var grossPaid=paid.add(refunded);
        String refund=refunded.signum()>0?(refunded.compareTo(grossPaid)>=0?"REFUNDED":"PARTIALLY_REFUNDED"):pending?"PENDING":"NONE";
        return new RenewalStatusSummary.Invoice(status,refund,original.add(terms.discountAmount()),terms.discountAmount(),credit,
            total,paid,outstanding,due,overdue,refunded,"VND",null);
    }
    private static RenewalStatusSummary.Transaction transaction(Payment p,boolean reconciled,BigDecimal refunded,Instant now) {
        String status=RenewalTransactionStatus.resolve(p,reconciled,refunded,now);
        return new RenewalStatusSummary.Transaction(p.getId(),p.getGatewayIntentId(),p.getPurpose().name(),status,p.getAmount(),refunded,
            p.getCurrency(),p.getPaymentDeadline(),p.getPaymentDeadline()!=null&&!now.isBefore(p.getPaymentDeadline()),
            reconciled&&Set.of("SUCCEEDED","PARTIALLY_REFUNDED","REFUNDED").contains(status),p.getPaidAt());
    }
    static String workflow(Renewal n,RenewalOperationState state,RenewalWorkflow workflow,List<RenewalStatusSummary.Transaction> transactions,Instant now) {
        if(n.getStatus()==RenewalStatus.completed)return "COMPLETED";
        if(n.getStatus()==RenewalStatus.cancelled)return "CANCELLED";
        if(n.getStatus()==RenewalStatus.rejected)return "REJECTED";
        if(state!=null&&(state.getPhase()==RenewalOperationState.Phase.SIGNING_EXPIRED
            ||state.getPhase()==RenewalOperationState.Phase.SIGNING&&state.getEffectiveSigningDeadline()!=null&&now.isAfter(state.getEffectiveSigningDeadline())))return "SIGNING_EXPIRED";
        if(transactions.stream().anyMatch(p->"RECONCILIATION_REQUIRED".equals(p.status())))return "AWAITING_RECONCILIATION";
        if(transactions.stream().anyMatch(p->Set.of("REFUNDED","PARTIALLY_REFUNDED").contains(p.status())))return "ON_HOLD";
        if(state!=null&&state.getPhase()==RenewalOperationState.Phase.SIGNING&&state.hasVerifiedPayment()
            &&transactions.stream().anyMatch(p->p.receiptAvailable()&&"RENEWAL".equals(p.purpose())))
            return state.getArrivalRef()==null?"AWAITING_SIGNING":"SIGNING_IN_PROGRESS";
        if(transactions.stream().anyMatch(p->p.receiptAvailable()&&"RENEWAL".equals(p.purpose())))return "AWAITING_RECONCILIATION";
        if(n.getStatus()==RenewalStatus.payment_expired||workflow!=null&&workflow.getPaymentDeadline()!=null&&!now.isBefore(workflow.getPaymentDeadline()))return "PAYMENT_WINDOW_EXPIRED";
        if(n.getStatus()==RenewalStatus.pending)return "UNDER_REVIEW";
        if(Set.of(RenewalStatus.deposit_paid,RenewalStatus.full_rent_paid,RenewalStatus.appointment_scheduled).contains(n.getStatus()))return "AWAITING_RECONCILIATION";
        return "AWAITING_PAYMENT";
    }
}
