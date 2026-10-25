package com.storagehub.service.ledger;

import com.fasterxml.jackson.databind.*;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.*;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.storagehub.service.renewal.operations.RenewalOperationSources.*;

/** Deposit disposition and staff-attested refunds share the original Payment, Ledger and Rental lock. */
@Service @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class RenewalDepositSettlementService implements RefundSource {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final LedgerStore ledger;
    private final PaymentRepository payments;
    private final PaymentProvenance provenance;
    private final RenewalPersistence store;
    private final AuditLogService audit;
    private final NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired
    private RenewalDepositFaultGuard faultGuard;
    private final ObjectProvider<Clock> clocks;
    private final ObjectProvider<PolicySource> policies;
    private final ObjectProvider<CalendarSource> calendars;
    private final ObjectProvider<SafetySource> safeties;
    private final ObjectProvider<RenewalOperationService> operations;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<RenewalLedgerAdjustments> cancellationCredits;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<RenewalDepositForfeitures> forfeitures;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.overdue.OverdueSources.TermSource> overdueTerms;
    public record View(UUID renewalId,Long expectedVersion,String status,BigDecimal amount,BigDecimal refundedAmount,
        String currency,String environment,UUID refundId,Instant signingDeadline,Instant checkedAt) {}
    private record Proof(RenewalOperationState state,RenewalWorkflow workflow,Payment payment,LedgerStore.Snapshot account,
        LedgerStore.Obligation obligation,LedgerStore.Receipt receipt,String environment) {}
    @Override public boolean atomic(){return true;}

    @Transactional(readOnly=true)
    public View read(UUID id) {
        var n=em.find(Renewal.class,id);if(n==null)throw ApiExceptions.notFound("Renewal not found");
        var s=em.find(RenewalOperationState.class,id);var w=em.find(RenewalWorkflow.class,id);Instant now=now();
        if(s==null||s.getDepositPaymentRef()==null)return new View(id,w==null?null:w.getVersion(),s!=null&&s.getFullRentPaidAt()!=null?"NOT_APPLICABLE":"UNKNOWN",null,null,"VND",null,null,s==null?null:s.getEffectiveSigningDeadline(),now);
        var p=proof(n,false);
        var refunds=refundRows(p.payment());
        var refund=refunds.stream().filter(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())).findFirst().orElse(null);
        String status=n.getStatus()==RenewalStatus.completed?"APPLIED_TO_RENT":"HELD";
        if(refund!=null)status=refund.getStatus()==RefundStatus.COMPLETED?"REFUNDED":"REFUND_APPROVED";
        else {
            var events=events(n,"DEPOSIT_DISPOSITION");
            if(!events.isEmpty())status=json(events.getFirst()).path("status").asText();
            else {
                var held=faultGuard.holdReason(n,s);
                if(held!=null)status=held;
                else if(now.isAfter(s.getEffectiveSigningDeadline()))status="AWAITING_RECOVERY_DEADLINE";
            }
        }
        return new View(id,p.workflow().getVersion(),status,p.payment().getAmount(),p.payment().getRefundedAmount(),"VND",p.environment(),refund==null?null:refund.getId(),s.getEffectiveSigningDeadline(),now);
    }
    @Transactional(readOnly=true)
    public List<UUID> candidates() {
        return em.createQuery("select s.renewal.id from RenewalOperationState s where s.depositPaidAt is not null "
            +"and s.effectiveSigningDeadline<:now and s.phase<>:completed order by s.effectiveSigningDeadline,s.renewal.id",UUID.class)
            .setParameter("now",now()).setParameter("completed",RenewalOperationState.Phase.COMPLETED).getResultList();
    }
    public boolean reconcile(UUID id) {
        var n=lock(id);var s=em.find(RenewalOperationState.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(n.getStatus()==RenewalStatus.completed||s==null||s.getDepositPaidAt()==null||s.getEffectiveSigningDeadline()==null||!now().isAfter(s.getEffectiveSigningDeadline()))return false;
        var source=overdueTerms.getIfAvailable();
        var term=source==null?null:source.term(n.getRental(),now()).orElse(null);
        if(term==null||term.recoveryFromDay()!=8||term.recoveryStart()==null||term.recoveryCutoff()==null
            ||!term.recoveryStart().isAfter(term.recoveryCutoff()))throw ApiExceptions.conflict("Verified day-eight forfeiture deadline required");
        if(now().isBefore(term.recoveryStart()))return false;
        var holdReason=faultGuard.holdReason(n,s);
        if(holdReason!=null) {
            if(events(n,"DEPOSIT_REVIEW_REQUIRED").isEmpty()) {
                em.persist(new RenewalOperationEvent(n,"DEPOSIT_REVIEW_REQUIRED",now(),null,store.canonical(Map.of("status",holdReason))));
                notifications.createFacilityOperationsNotifications(n.getRental().getFacility().getId(),NotificationType.RENEWAL,
                    "Cọc gia hạn cần được đối soát trách nhiệm","Có sự cố từ cơ sở hoặc trách nhiệm chưa được xác minh; chưa ghi nhận mất cọc.",id);
            }
            return false;
        }
        var p=proof(n,true);requireReconciled(n);
        if(refundRows(p.payment()).stream().anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))return false;
        if(p.payment().getRefundedAmount().signum()!=0)throw ApiExceptions.conflict("Refunded deposit requires reconciliation before forfeiture");
        operations.getObject().expire(id);
        String status="FORFEITED";
        var accounting=forfeitures.getIfAvailable();
        if(accounting==null)throw ApiExceptions.conflict("Deposit forfeiture accounting unavailable");
        accounting.record(id,p.payment().getId(),p.account(),p.obligation(),p.receipt(),p.environment(),now());
        var old=events(n,"DEPOSIT_DISPOSITION");
        if(!old.isEmpty()&&status.equals(json(old.getFirst()).path("status").asText()))return false;
        if(status.equals("FORFEITED")) {
            cancelRemainder(n,p);n.setStatus(RenewalStatus.cancelled);p.workflow().cancelled("OVERDUE_DAY_8");releaseOwnSlot(p.workflow());
        }
        var data=Map.of("status",status,"amount",p.payment().getAmount(),"currency","VND","paymentId",p.payment().getId(),
            "environment",p.environment(),"reason","OVERDUE_DAY_8");
        var e=new RenewalOperationEvent(n,"DEPOSIT_DISPOSITION",now(),null,store.canonical(data));em.persist(e);em.flush();
        em.lock(p.workflow(),LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        audit.recordMutation((User)null,"RENEWAL_DEPOSIT_DISPOSITION","Renewal",id,n.getRental().getFacility().getId(),null,data);
        if(status.equals("FORFEITED"))notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.PAYMENT,
            "Đã ghi nhận mất cọc gia hạn","Hợp đồng đã sang ngày quá hạn thứ 8 và gia hạn chưa hoàn tất. Cọc gia hạn được ghi nhận không hoàn lại.",id);
        return true;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundReservation reserve(Renewal n,UUID incident,UUID actor,String key,Instant now) {
        if(n.getStatus()==RenewalStatus.completed)throw ApiExceptions.conflict("Completed Renewal deposit cannot be refunded as a signing failure");
        var p=proof(n,true);requireReconciled(n);requireValidIncident(n,p,incident);
        if(forfeitures.getObject().containsReceipt(p.receipt().id())||events(n,"DEPOSIT_DISPOSITION").stream().anyMatch(e->"FORFEITED".equals(json(e).path("status").asText())))
            throw ApiExceptions.conflict("Forfeited Renewal deposit is not refundable");
        var policy=policies.getIfAvailable();if(policy==null)throw ApiExceptions.conflict("Facility fault classification source missing");
        policy.requireFacilityFault(n,incident,actor);
        if(now.isBefore(p.state().getEffectiveSigningDeadline())) {
            var calendar=calendars.getIfAvailable();
            var feasible=calendar==null?Optional.<Boolean>empty():calendar.feasible(n.getRental(),now,p.state().getEffectiveSigningDeadline());
            if(feasible.isEmpty())throw ApiExceptions.conflict("Remaining signing availability is unknown");
            if(feasible.get())throw ApiExceptions.conflict("A valid signing slot is still available; arrange a replacement appointment");
        }
        if(refundRows(p.payment()).stream().anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))
            throw ApiExceptions.conflict("Deposit already has a pending or completed refund");
        var amount=p.payment().getAmount().subtract(p.payment().getRefundedAmount());
        if(amount.signum()<=0)throw ApiExceptions.conflict("Deposit has no refundable balance");
        String decision="renewal-refund:"+n.getId()+":"+p.payment().getId();
        UUID ledgerRef=p.environment().equals("SANDBOX")?ledger.reserveSandboxRenewalRefund(n.getRental().getId(),p.receipt(),p.obligation(),
            new LedgerStore.GatewayProof(p.payment().getId(),p.payment().getRenewalEvidenceRef(),p.payment().getGatewayTransactionNo()),amount,decision,actor,key,p.account().revision())
            :ledger.reserveRefund(n.getRental().getId(),p.receipt().id(),p.obligation().id(),amount,decision,actor,key,p.account().revision());
        var refund=new Refund();refund.setPayment(p.payment());refund.setRequestedBy(em.find(User.class,actor));refund.setAmount(amount);
        refund.setReason("FACILITY_FAULT:"+incident);em.persist(refund);em.flush();
        em.persist(new RenewalOperationEvent(n,"REFUND_RESERVATION",now,actor,store.canonical(Map.of("refundId",refund.getId(),"ledgerRefundId",ledgerRef,"paymentId",p.payment().getId(),"environment",p.environment()))));
        var safety=safeties.getIfAvailable();var calendar=calendars.getIfAvailable();
        if(safety==null||!safety.atomic()||calendar==null||!calendar.atomic())throw ApiExceptions.conflict("Transactional hold/calendar release required for refund approval");
        if(p.workflow().getExtensionHoldRef()!=null)safety.release(n,p.workflow().getExtensionHoldRef());
        cancelRemainder(n,p);
        calendar.release(n,now);p.state().expire(true);n.setStatus(RenewalStatus.cancelled);p.workflow().cancelled("FACILITY_FAULT_REFUND");releaseOwnSlot(p.workflow());
        return new RefundReservation(refund.getId(),n.getId(),amount,"VND",now,now);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ExecutedRefund execute(Renewal n,UUID id,UUID file,String reference,UUID actor,Instant now) {
        var p=proof(n,true);requireReconciled(n);
        var refund=em.find(Refund.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(refund==null||!refund.getPayment().getId().equals(p.payment().getId()))throw ApiExceptions.notFound("Refund not found for this Renewal");
        if(refund.getRequestedBy()!=null&&refund.getRequestedBy().getId().equals(actor))
            throw ApiExceptions.conflict("The refund proposer cannot execute the same refund");
        if(refund.getStatus()!=RefundStatus.PENDING)throw ApiExceptions.conflict("Refund is not awaiting execution");
        var reservations=events(n,"REFUND_RESERVATION").stream().filter(e->id.toString().equals(json(e).path("refundId").asText())).toList();
        if(reservations.size()!=1)throw ApiExceptions.conflict("Refund reservation binding invalid");
        var data=json(reservations.getFirst());UUID ledgerRef=UUID.fromString(data.path("ledgerRefundId").asText());
        if(refund.getAmount().compareTo(p.payment().getAmount().subtract(p.payment().getRefundedAmount()))>0)
            throw ApiExceptions.conflict("Refund exceeds the unrefunded deposit");
        String payout=(p.environment().equals("SANDBOX")?"sandbox-attestation:":"manual-payout:")+reference;
        ledger.executeRefund(n.getRental().getId(),ledgerRef,payout,actor,"renewal-refund-execution:"+id,p.account().revision());
        var after=ledger.read(n.getRental().getId()).orElseThrow();
        var deposit=after.obligations().stream().filter(o->o.id().equals(p.obligation().id())).findFirst().orElseThrow();
        credits().cancelOutstanding(after,deposit,refund.getAmount(),"refund-deposit:"+id,"CANCELLED_RENEWAL_DEPOSIT_REFUND");
        refund.setStatus(RefundStatus.COMPLETED);refund.setGatewayRefundId(reference);p.payment().setRefundedAmount(p.payment().getRefundedAmount().add(refund.getAmount()));
        notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.PAYMENT,"Đã ghi nhận hoàn cọc gia hạn",
            p.environment().equals("SANDBOX")?"Đã ghi nhận chứng từ hoàn cọc trong môi trường Sandbox.":"Cơ sở đã xác nhận hoàn cọc gia hạn và lưu chứng từ thanh toán.",n.getId());
        return new ExecutedRefund(id,n.getId(),refund.getAmount(),"VND",now,p.environment());
    }
    private Proof proof(Renewal n,boolean write) {
        var s=em.find(RenewalOperationState.class,n.getId());var w=em.find(RenewalWorkflow.class,n.getId());
        if(s==null||s.getDepositPaymentRef()==null||s.getDepositPaidAt()==null||s.getEffectiveSigningDeadline()==null||w==null||w.getAcceptedRevision()==null)
            throw ApiExceptions.conflict("Verified deposit and accepted revision required");
        if(s.getRecoveryCutoff()==null||s.getOriginalSigningDeadline()==null
            ||!s.getOriginalSigningDeadline().equals(com.storagehub.service.renewal.RenewalSigningDeadline.calculate(s.getDepositPaidAt(),s.getRecoveryCutoff()))
            ||s.getEffectiveSigningDeadline().isAfter(s.getRecoveryCutoff())
            ||s.getConfirmedExceptionRef()==null&&!s.getEffectiveSigningDeadline().equals(s.getOriginalSigningDeadline()))
            throw ApiExceptions.conflict("Signing deadline requires reconciliation before deposit settlement");
        var payment=write?payments.findByIdForUpdate(s.getDepositPaymentRef()).orElse(null):em.find(Payment.class,s.getDepositPaymentRef());
        if(payment==null||payment.getStatus()!=PaymentStatus.PAID||payment.getPurpose()!=PaymentType.RENEWAL||payment.getRenewalRevision()==null
            ||!n.getId().equals(payment.getRenewal().getId())||!w.getAcceptedRevision().getId().equals(payment.getRenewalRevision().getId())
            ||payment.getRenewalLedgerReceiptRef()==null||payment.getRefundedAmount()==null||payment.getPaidAt()==null||!payment.getPaidAt().equals(s.getDepositPaidAt()))
            throw ApiExceptions.conflict("Deposit Payment binding is invalid");
        var classification=provenance.classify(payment);
        if(!Set.of(PaymentProvenance.Classification.SANDBOX,PaymentProvenance.Classification.VERIFIED).contains(classification))
            throw ApiExceptions.conflict("Deposit payment provenance has not been reconciled");
        final RenewalQuoteResponse.Terms accepted;
        try{accepted=mapper.readValue(w.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Accepted deposit terms invalid");}
        var specs=FinancialContractSpecifications.accepted(w.getAcceptedRevision(),accepted);
        if(specs.getFirst().kind()!=LedgerStore.Kind.RENEWAL_DEPOSIT||payment.getAmount().compareTo(specs.getFirst().amount())!=0)
            throw ApiExceptions.conflict("Payment is not the accepted Renewal deposit");
        var account=ledger.read(n.getRental().getId()).orElseThrow(()->ApiExceptions.conflict("Deposit ledger account missing"));
        if(!account.customerId().equals(n.getRental().getCustomer().getId())||!account.facilityId().equals(n.getRental().getFacility().getId()))throw ApiExceptions.conflict("Deposit ledger binding invalid");
        var obligation=account.obligations().stream().filter(o->o.kind()==LedgerStore.Kind.RENEWAL_DEPOSIT&&n.getId().equals(o.renewalId())
            &&specs.getFirst().sourceReference().equals(o.sourceRef())&&payment.getAmount().compareTo(o.amount())==0).findFirst().orElseThrow(()->ApiExceptions.conflict("Accepted deposit obligation missing"));
        var receipt=account.receipts().stream().filter(r->r.id().equals(payment.getRenewalLedgerReceiptRef())&&r.amount().compareTo(payment.getAmount())==0).findFirst().orElseThrow(()->ApiExceptions.conflict("Deposit receipt missing"));
        if(classification==PaymentProvenance.Classification.SANDBOX&&(payment.getRenewalEvidenceRef()==null
            ||!ledger.renewalReceiptMatches(n.getRental().getId(),receipt,new LedgerStore.GatewayProof(payment.getId(),payment.getRenewalEvidenceRef(),payment.getGatewayTransactionNo()),obligation)))
            throw ApiExceptions.conflict("Authenticated Sandbox deposit allocation missing");
        if(classification==PaymentProvenance.Classification.VERIFIED&&!ledger.renewalCashReceiptMatches(n.getRental().getId(),receipt,obligation))
            throw ApiExceptions.conflict("Actual deposit allocation evidence missing");
        return new Proof(s,w,payment,account,obligation,receipt,classification==PaymentProvenance.Classification.SANDBOX?"SANDBOX":"PRODUCTION");
    }
    private void requireValidIncident(Renewal n,Proof p,UUID incidentId) {
        var incident=em.find(RenewalOperationEvent.class,incidentId);
        if(incident==null||!incident.getKind().equals("INCIDENT")||!n.getId().equals(incident.getRenewal().getId()))throw ApiExceptions.notFound("Related incident not found");
        UUID appointmentId=UUID.fromString(json(incident).path("appointmentRef").asText());
        requireValidAppointment(n,p,appointmentId);
    }
    private void requireValidAppointment(Renewal n,Proof p,UUID appointmentId) {
        var appointment=em.find(RenewalOperationEvent.class,appointmentId);
        if(appointment==null||!appointment.getKind().equals("APPOINTMENT")||!n.getId().equals(appointment.getRenewal().getId()))throw ApiExceptions.conflict("Incident appointment is invalid");
        com.storagehub.service.renewal.operations.RenewalOperationSources.Slot slot;
        try {slot=mapper.treeToValue(json(appointment),com.storagehub.service.renewal.operations.RenewalOperationSources.Slot.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Incident appointment is invalid");}
        Instant start=slot.start(),end=slot.end();
        if(start==null||end==null)throw ApiExceptions.conflict("Incident appointment is invalid");
        if(start.isBefore(p.state().getDepositPaidAt())||!end.isAfter(start)||end.isAfter(p.state().getEffectiveSigningDeadline()))
            throw ApiExceptions.conflict("Incident did not affect a valid paid signing appointment");
    }
    private void releaseOwnSlot(RenewalWorkflow workflow) {
        // Recovery or an earlier expiry may already have released this slot.
        // A newer request on the same Rental must retain its own slot.
        var slot=em.find(RenewalOpenSlot.class,workflow.getRenewal().getRental().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(slot!=null&&slot.getRenewal().getId().equals(workflow.getId()))store.releaseSlot(workflow);
    }
    private void requireReconciled(Renewal n) {
        if(payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).stream().anyMatch(p->Set.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED).contains(p.getStatus())))
            throw ApiExceptions.conflict("Renewal payments require reconciliation before deposit settlement");
    }
    private void cancelRemainder(Renewal n,Proof p) {
        final boolean monthly;
        try{monthly="MONTHLY".equals(mapper.readTree(p.workflow().getAcceptedRevision().getQuote().getTermsJson()).path("paymentPlan").asText());}
        catch(Exception invalid){throw ApiExceptions.conflict("Điều khoản thanh toán đã lưu không hợp lệ.");}
        var account=ledger.read(n.getRental().getId()).orElseThrow();
        for(var o:account.obligations())if(Objects.equals(o.renewalId(),n.getId())&&o.kind()==LedgerStore.Kind.RENEWAL_REMAINDER
            &&o.sourceRef().startsWith("renewal-accepted:"+p.workflow().getAcceptedRevision().getId()+":")&&o.outstanding().signum()>0) {
            credits().cancelOutstanding(account,o,o.outstanding(),"void-remainder:"+n.getId()+(monthly?":"+o.id():""),"SIGNING_FAILURE_UNEARNED_RENT");
            account=ledger.read(n.getRental().getId()).orElseThrow();
        }
    }
    private RenewalLedgerAdjustments credits() {
        var source=cancellationCredits==null?null:cancellationCredits.getIfAvailable();
        if(source==null)throw ApiExceptions.conflict("Cancellation accounting source unavailable");return source;
    }
    private List<Refund> refundRows(Payment payment) {
        return em.createQuery("select r from Refund r where r.payment.id=:id order by r.createdAt desc,r.id desc",Refund.class).setParameter("id",payment.getId()).getResultList();
    }
    private List<RenewalOperationEvent> events(Renewal n,String kind) {
        return em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind=:kind order by e.occurredAt desc,e.id desc",RenewalOperationEvent.class)
            .setParameter("id",n.getId()).setParameter("kind",kind).getResultList();
    }
    private JsonNode json(RenewalOperationEvent e) {try{return mapper.readTree(e.getPayloadJson());}catch(Exception invalid){throw ApiExceptions.conflict("Deposit settlement event is invalid");}}
    private Renewal lock(UUID id) {
        var n=em.find(Renewal.class,id);if(n==null)throw ApiExceptions.notFound("Renewal not found");
        var rental=store.lockRental(n.getRental().getId());em.refresh(rental);em.refresh(n);return n;
    }
    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
}
