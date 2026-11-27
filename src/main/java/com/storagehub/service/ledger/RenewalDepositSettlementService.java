package com.storagehub.service.ledger;

import com.fasterxml.jackson.databind.*;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.*;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.*;
import com.storagehub.service.renewal.RenewalPreparationResolution;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.storagehub.service.renewal.operations.RenewalOperationSources.*;

/** Deposit disposition and staff-attested refunds share the original Payment, Ledger and Rental lock. */
@Service @RequiredArgsConstructor
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
        String currency,String environment,UUID refundId,Instant signingDeadline,Instant checkedAt,
        UUID preparationPaymentId,boolean preparationRetryAllowed) {
        public View(UUID renewalId,Long expectedVersion,String status,BigDecimal amount,BigDecimal refundedAmount,
            String currency,String environment,UUID refundId,Instant signingDeadline,Instant checkedAt) {
            this(renewalId,expectedVersion,status,amount,refundedAmount,currency,environment,refundId,signingDeadline,checkedAt,null,false);
        }
    }
    private record Proof(RenewalOperationState state,RenewalWorkflow workflow,Payment payment,LedgerStore.Snapshot account,
        LedgerStore.Obligation obligation,LedgerStore.Receipt receipt,String environment) {}
    @Override public boolean atomic(){return true;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void forfeitCustomerCancellation(Renewal n,User customer) {
        if (n.getStatus()==RenewalStatus.completed || !n.getRental().getCustomer().getId().equals(customer.getId()))
            throw ApiExceptions.conflict("Không thể tự hủy gia hạn đã hoàn tất hoặc của khách hàng khác.");
        var p=proof(n,true);requireReconciled(n);
        if (p.state()==null || p.state().getFullRentPaidAt()!=null || faultGuard.holdReason(n,p.state())!=null)
            throw ApiExceptions.conflict("Hồ sơ cần cơ sở đối soát trước khi hủy gia hạn.");
        if (payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).stream()
            .anyMatch(other->other.getStatus()==PaymentStatus.PAID && !other.getId().equals(p.payment().getId())))
            throw ApiExceptions.conflict("Đã thu thêm tiền gia hạn; cần cơ sở đối soát trước khi hủy.");
        if (p.payment().getRefundedAmount().signum()!=0 || refundRows(p.payment()).stream()
            .anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))
            throw ApiExceptions.conflict("Khoản cọc đang được hoàn hoặc đối soát, không thể tự hủy.");
        var accounting=forfeitures.getIfAvailable();
        if (accounting==null) throw ApiExceptions.conflict("Chưa thể ghi nhận cọc gia hạn không hoàn lại.");
        accounting.record(n.getId(),p.payment().getId(),p.account(),p.obligation(),p.receipt(),p.environment(),now(),"CUSTOMER_CANCELLED");
        cancelRemainder(n,p);
        p.state().expire(true);
        var data=Map.of("status","FORFEITED","amount",p.payment().getAmount(),"currency","VND",
            "paymentId",p.payment().getId(),"environment",p.environment(),"reason","CUSTOMER_CANCELLED");
        em.persist(new RenewalOperationEvent(n,"DEPOSIT_DISPOSITION",now(),customer.getId(),store.canonical(data)));
        audit.recordMutation(customer,"RENEWAL_CUSTOMER_CANCELLED_DEPOSIT_FORFEITED","Renewal",n.getId(),n.getRental().getFacility().getId(),null,data);
        notifications.createNotification(customer.getId(),NotificationType.RENEWAL,"Đã hủy yêu cầu gia hạn",
            "Bạn đã tự hủy gia hạn. Tiền cọc gia hạn không được hoàn lại; hợp đồng thuê hiện tại giữ nguyên thời hạn.",n.getId());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void forfeitFullyPaidCustomerCancellation(Renewal n,User customer) {
        if(n.getStatus()==RenewalStatus.completed||!n.getRental().getCustomer().getId().equals(customer.getId()))
            throw ApiExceptions.conflict("Không thể tự hủy gia hạn đã hoàn tất hoặc của khách hàng khác.");
        var state=em.find(RenewalOperationState.class,n.getId());
        var workflow=em.find(RenewalWorkflow.class,n.getId());
        if(state==null||!state.hasVerifiedPayment()||state.getCompletedAt()!=null||workflow==null||workflow.getAcceptedRevision()==null
            ||faultGuard.holdReason(n,state)!=null)
            throw ApiExceptions.conflict("Hồ sơ cần cơ sở đối soát trước khi hủy gia hạn.");
        requireReconciled(n);
        final RenewalQuoteResponse.Terms terms;
        try {terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Điều khoản gia hạn đã lưu không hợp lệ.");}
        var specs=FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms);
        var account=ledger.read(n.getRental().getId()).orElseThrow(()->ApiExceptions.conflict("Chưa có sổ thanh toán gia hạn."));
        if(!account.customerId().equals(customer.getId())||!account.facilityId().equals(n.getRental().getFacility().getId()))
            throw ApiExceptions.conflict("Thông tin thanh toán gia hạn không khớp hồ sơ.");
        var received=payments.findRenewalPaymentsForUpdate(n.getId()).stream().filter(p->p.getStatus()==PaymentStatus.PAID).toList();
        var allAllocations=ledger.renewalAllocations(account.rentalId(),n.getId());
        var dispositions=new ArrayList<RenewalDepositForfeitures.PaidReceipt>();
        for(var payment:received) {
            if(!Set.of(PaymentType.RENEWAL,PaymentType.RENTAL_BALANCE).contains(payment.getPurpose())||payment.getRenewalRevision()==null
                ||!workflow.getAcceptedRevision().getId().equals(payment.getRenewalRevision().getId())
                ||payment.getRenewalAllocatedAt()==null||payment.getPaidAt()==null||payment.getPaidAt().isAfter(now())
                ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0
                ||refundRows(payment).stream().anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))
                throw ApiExceptions.conflict("Khoản thanh toán đang cần đối soát hoặc hoàn tiền, không thể tự hủy.");
            var receipt=account.receipts().stream().filter(r->r.id().equals(payment.getRenewalLedgerReceiptRef())
                &&r.amount().compareTo(payment.getAmount())==0&&r.receivedAt().toEpochMilli()==payment.getPaidAt().toEpochMilli())
                .findFirst().orElseThrow(()->ApiExceptions.conflict("Chưa xác minh được biên nhận tiền gia hạn."));
            var allocation=allAllocations.stream().filter(a->a.receiptId().equals(receipt.id())).toList();
            var classification=provenance.classify(payment);
            if(classification==PaymentProvenance.Classification.SANDBOX) {
                if(allocation.size()!=1||payment.getRenewalEvidenceRef()==null)
                    throw ApiExceptions.conflict("Chưa xác minh được phân bổ thanh toán gia hạn.");
                var obligation=account.obligations().stream().filter(o->o.id().equals(allocation.getFirst().obligationId())).findFirst().orElseThrow();
                if(!ledger.renewalReceiptMatches(account.rentalId(),receipt,new LedgerStore.GatewayProof(payment.getId(),payment.getRenewalEvidenceRef(),payment.getGatewayTransactionNo()),obligation))
                    throw ApiExceptions.conflict("Bằng chứng thanh toán gia hạn chưa hợp lệ.");
            } else if(classification==PaymentProvenance.Classification.VERIFIED) {
                var manual=ledger.renewalManualAllocations(account.rentalId(),receipt);
                if(manual.isEmpty()||manual.size()!=allocation.size()||allocation.stream().anyMatch(a->manual.get(a.obligationId())==null||manual.get(a.obligationId()).compareTo(a.amount())!=0))
                    throw ApiExceptions.conflict("Bằng chứng thu tiền gia hạn chưa hợp lệ.");
            } else throw ApiExceptions.conflict("Khoản thanh toán gia hạn chưa được xác minh.");
            dispositions.add(new RenewalDepositForfeitures.PaidReceipt(payment.getId(),receipt,
                classification==PaymentProvenance.Classification.SANDBOX?"SANDBOX":"PRODUCTION"));
        }
        var receipts=dispositions.stream().map(p->p.receipt().id()).collect(java.util.stream.Collectors.toSet());
        if(receipts.size()!=received.size()||received.isEmpty())throw ApiExceptions.conflict("Biên nhận gia hạn không đầy đủ.");
        for(var spec:specs) {
            if(spec.amount().signum()==0)continue;
            var obligation=account.obligations().stream().filter(o->n.getId().equals(o.renewalId())&&o.kind()==spec.kind()
                &&o.sourceRef().equals(spec.sourceReference())&&o.amount().compareTo(spec.amount())==0).findFirst()
                .orElseThrow(()->ApiExceptions.conflict("Chưa ghi nhận đủ tiền gia hạn theo điều khoản đã chấp nhận."));
            var allocated=allAllocations.stream().filter(a->a.obligationId().equals(obligation.id())&&receipts.contains(a.receiptId()))
                .map(LedgerStore.Allocation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(obligation.refunded().signum()!=0||obligation.outstanding().signum()!=0||allocated.compareTo(spec.amount())!=0)
                throw ApiExceptions.conflict("Tiền gia hạn chưa được thanh toán đủ hoặc đang được hoàn.");
        }
        var accounting=forfeitures.getIfAvailable();
        if(accounting==null)throw ApiExceptions.conflict("Chưa thể ghi nhận tiền gia hạn không hoàn lại.");
        accounting.recordFullyPaid(n.getId(),account,dispositions,now());
        state.expire(true);
        var total=received.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        var data=Map.of("status","FORFEITED","amount",total,"currency","VND","reason","CUSTOMER_CANCELLED");
        em.persist(new RenewalOperationEvent(n,"PAYMENT_DISPOSITION",now(),customer.getId(),store.canonical(data)));
        audit.recordMutation(customer,"RENEWAL_CUSTOMER_CANCELLED_PAYMENT_FORFEITED","Renewal",n.getId(),n.getRental().getFacility().getId(),null,data);
        notifications.createNotification(customer.getId(),NotificationType.RENEWAL,"Đã hủy yêu cầu gia hạn",
            "Bạn đã tự hủy gia hạn và xác nhận toàn bộ tiền gia hạn đã thanh toán không được hoàn lại. Hợp đồng thuê hiện tại giữ nguyên thời hạn.",n.getId());
    }

    @Transactional(readOnly=true)
    public View read(UUID id) {
        var n=em.find(Renewal.class,id);if(n==null)throw ApiExceptions.notFound("Renewal not found");
        var s=em.find(RenewalOperationState.class,id);var w=em.find(RenewalWorkflow.class,id);Instant now=now();
        var accounting=forfeitures.getIfAvailable();
        var cancelledPayment=accounting==null?Optional.<RenewalDepositForfeitures.FullDisposition>empty():accounting.fullDisposition(id);
        if(n.getStatus()==RenewalStatus.cancelled&&cancelledPayment.isPresent()) {
            var disposition=cancelledPayment.get();
            return new View(id,w==null?null:w.getVersion(),"PAYMENT_FORFEITED",disposition.amount(),BigDecimal.ZERO,"VND",disposition.environment(),null,s==null?null:s.getEffectiveSigningDeadline(),now);
        }
        if((s==null||!s.hasVerifiedPayment())&&payments.findAllByRenewal_IdOrderByCreatedAtAsc(id).stream().anyMatch(RenewalPreparationResolution::blocked)) {
            var p=proof(n,false);
            var refund=refundRows(p.payment()).stream().filter(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())).findFirst().orElse(null);
            String status=refund==null?"PREPARATION_BLOCKED":refund.getStatus()==RefundStatus.COMPLETED?"REFUNDED":"REFUND_APPROVED";
            var deadline=RenewalPreparationResolution.deadline(p.payment(),s,mapper);
            return new View(id,p.workflow().getVersion(),status,p.payment().getAmount(),p.payment().getRefundedAmount(),"VND",p.environment(),
                refund==null?null:refund.getId(),deadline,now,p.payment().getId(),n.getStatus()==RenewalStatus.accepted&&refund==null&&deadline!=null&&now.isBefore(deadline));
        }
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
            +"and s.effectiveSigningDeadline<:now and s.phase<>:completed "
            +"and not exists (select e.id from RenewalOperationEvent e where e.renewal=s.renewal and e.kind='PAYMENT_DISPOSITION') "
            +"order by s.effectiveSigningDeadline,s.renewal.id",UUID.class)
            .setParameter("now",now()).setParameter("completed",RenewalOperationState.Phase.COMPLETED).getResultList();
    }
    public boolean reconcile(UUID id) {
        var n=lock(id);var s=em.find(RenewalOperationState.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(n.getStatus()==RenewalStatus.cancelled&&forfeitures!=null) {
            var disposed=forfeitures.getIfAvailable();
            if(disposed!=null&&disposed.fullDisposition(id).isPresent())return false;
        }
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
                    "Tiền thuê gia hạn trả trước cần được đối soát trách nhiệm","Có sự cố từ cơ sở hoặc trách nhiệm chưa được xác minh; chưa quyết định khoản trả trước không được hoàn.",id);
                notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.RENEWAL,
                    "Khoản trả trước gia hạn đang được đối soát",
                    "Khoản đã thu đang được giữ nguyên trong khi cơ sở xác minh trách nhiệm và phương án xử lý. Chưa có quyết định hoàn hoặc không hoàn tiền.",id);
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
        if(status.equals("FORFEITED")) {
            notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.PAYMENT,
                "Tiền thuê gia hạn trả trước không được hoàn","Hợp đồng đã sang ngày quá hạn thứ 8 và gia hạn chưa hoàn tất. Tiền thuê gia hạn trả trước được ghi nhận không hoàn lại.",id);
            notifications.createFacilityOperationsNotifications(n.getRental().getFacility().getId(),NotificationType.PAYMENT,
                "Đã chốt khoản trả trước gia hạn không hoàn lại",
                "Khoản trả trước gia hạn của khách hàng "+n.getRental().getCustomer().getFullName()+" đã được chốt không hoàn lại sau đối soát quá hạn ngày thứ 8.",id);
        }
        return true;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundReservation reserve(Renewal n,UUID incident,UUID actor,String key,Instant now) {
        if(n.getStatus()==RenewalStatus.completed)throw ApiExceptions.conflict("Completed Renewal deposit cannot be refunded as a signing failure");
        var p=proof(n,true);requireReconciled(n);
        if(p.state()==null||!p.state().hasVerifiedPayment())throw ApiExceptions.conflict("Use preparation resolution for a payment without verified signing state");
        requireValidIncident(n,p,incident);
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
        return reserveVerified(n,p,actor,key,now,"FACILITY_FAULT:"+incident);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RefundReservation reservePreparation(Renewal n,UUID paymentId,UUID actor,String key,Instant now) {
        var p=preparationProof(n,paymentId);requireReconciled(n);
        if(payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).stream().anyMatch(other->other.getStatus()==PaymentStatus.PAID&&!other.getId().equals(paymentId)))
            throw ApiExceptions.conflict("Additional renewal collections require reconciliation before preparation refund");
        return reserveVerified(n,p,actor,key,now,"SIGNING_PREPARATION_FAILED");
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void retryPreparation(Renewal n,UUID paymentId,Instant now) {
        if(n.getStatus()!=RenewalStatus.accepted)throw ApiExceptions.conflict("Cancelled Renewal cannot resume preparation");
        var p=preparationProof(n,paymentId);requireReconciled(n);
        if(refundRows(p.payment()).stream().anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))
            throw ApiExceptions.conflict("Refund already reserved; preparation cannot resume");
        var source=overdueTerms.getIfAvailable();var term=source==null?null:source.term(n.getRental(),now).orElse(null);
        var deadline=RenewalPreparationResolution.deadline(p.payment(),p.state(),mapper);
        if(term==null||term.recoveryCutoff()==null||deadline==null||!now.isBefore(deadline)||!now.isBefore(term.recoveryCutoff()))
            throw ApiExceptions.conflict("Signing/recovery deadline prevents preparation retry");
        p.payment().setFailureCode(RenewalPreparationResolution.RETRY);
        p.payment().setFailureReason("Manager requested another bounded signing preparation attempt");
        p.payment().setRenewalReconciliationAttempts(0);p.payment().setRenewalNextRetryAt(now);
    }
    private Proof preparationProof(Renewal n,UUID paymentId) {
        var workflow=em.find(RenewalWorkflow.class,n.getId());
        if(n.getStatus()!=RenewalStatus.accepted&&!(n.getStatus()==RenewalStatus.cancelled&&workflow!=null
            &&"AUTO_RECOVERY_DAY_8".equals(workflow.getCancellationReason())))
            throw ApiExceptions.conflict("Only a held or recovery-cancelled Renewal may resolve signing preparation");
        var p=proof(n,true);
        if(!paymentId.equals(p.payment().getId())||!RenewalPreparationResolution.blocked(p.payment())
            ||p.state()!=null&&p.state().hasVerifiedPayment())throw ApiExceptions.conflict("Payment is not awaiting signing preparation resolution");
        return p;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean preparationPaymentReconciled(Renewal n) {
        var attempts=payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId());
        if(attempts.stream().noneMatch(RenewalPreparationResolution::blocked))return false;
        var p=proof(n,false);requireReconciled(n);
        return attempts.stream().filter(other->other.getStatus()==PaymentStatus.PAID).count()==1
            &&RenewalPreparationResolution.blocked(p.payment());
    }
    private RefundReservation reserveVerified(Renewal n,Proof p,UUID actor,String key,Instant now,String reason) {
        if(forfeitures.getObject().containsReceipt(p.receipt().id()))throw ApiExceptions.conflict("Forfeited payment requires reconciliation before refund");
        if(refundRows(p.payment()).stream().anyMatch(r->Set.of(RefundStatus.PENDING,RefundStatus.PROCESSING,RefundStatus.COMPLETED).contains(r.getStatus())))
            throw ApiExceptions.conflict("Deposit already has a pending or completed refund");
        var amount=p.payment().getAmount().subtract(p.payment().getRefundedAmount());
        if(amount.signum()<=0)throw ApiExceptions.conflict("Deposit has no refundable balance");
        String decision="renewal-refund:"+n.getId()+":"+p.payment().getId();
        UUID ledgerRef=p.environment().equals("SANDBOX")?ledger.reserveSandboxRenewalRefund(n.getRental().getId(),p.receipt(),p.obligation(),
            new LedgerStore.GatewayProof(p.payment().getId(),p.payment().getRenewalEvidenceRef(),p.payment().getGatewayTransactionNo()),amount,decision,actor,key,p.account().revision())
            :ledger.reserveRefund(n.getRental().getId(),p.receipt().id(),p.obligation().id(),amount,decision,actor,key,p.account().revision());
        var refund=new Refund();refund.setPayment(p.payment());refund.setRequestedBy(em.find(User.class,actor));refund.setAmount(amount);
        refund.setReason(reason);em.persist(refund);em.flush();
        em.persist(new RenewalOperationEvent(n,"REFUND_RESERVATION",now,actor,store.canonical(Map.of("refundId",refund.getId(),"ledgerRefundId",ledgerRef,"paymentId",p.payment().getId(),"environment",p.environment()))));
        var safety=safeties.getIfAvailable();var calendar=calendars.getIfAvailable();
        if(safety==null||!safety.atomic()||calendar==null||!calendar.atomic())throw ApiExceptions.conflict("Transactional hold/calendar release required for refund approval");
        if(p.workflow().getExtensionHoldRef()!=null)safety.release(n,p.workflow().getExtensionHoldRef());
        cancelRemainder(n,p);
        calendar.release(n,now);
        if(p.state()!=null&&p.state().hasVerifiedPayment())p.state().expire(true);
        n.setStatus(RenewalStatus.cancelled);p.workflow().cancelled(reason.equals("SIGNING_PREPARATION_FAILED")?"PREPARATION_FAILURE_REFUND":"FACILITY_FAULT_REFUND");releaseOwnSlot(p.workflow());
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
        refund.setStatus(RefundStatus.COMPLETED);refund.setExecutedAt(now);refund.setGatewayRefundId(reference);p.payment().setRefundedAmount(p.payment().getRefundedAmount().add(refund.getAmount()));
        notifications.createNotification(n.getRental().getCustomer().getId(),NotificationType.PAYMENT,"Đã ghi nhận hoàn tiền thuê gia hạn trả trước",
            p.environment().equals("SANDBOX")?"Đã ghi nhận chứng từ hoàn tiền thuê gia hạn trả trước trong môi trường Sandbox.":"Cơ sở đã xác nhận hoàn tiền thuê gia hạn trả trước và lưu chứng từ thanh toán.",n.getId());
        return new ExecutedRefund(id,n.getId(),refund.getAmount(),"VND",now,p.environment());
    }
    private Proof proof(Renewal n,boolean write) {
        var s=em.find(RenewalOperationState.class,n.getId());var w=em.find(RenewalWorkflow.class,n.getId());
        if(w==null||w.getAcceptedRevision()==null)throw ApiExceptions.conflict("Verified accepted revision required");
        final RenewalQuoteResponse.Terms accepted;
        try{accepted=mapper.readValue(w.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}
        catch(Exception invalid){throw ApiExceptions.conflict("Accepted deposit terms invalid");}
        boolean unprepared=s==null||!s.hasVerifiedPayment();
        Payment payment;
        if(unprepared) {
            var candidates=payments.findAllByRenewal_IdOrderByCreatedAtAsc(n.getId()).stream()
                .filter(RenewalPreparationResolution::blocked).filter(candidate->candidate.getRenewalRevision()!=null
                    &&candidate.getRenewalRevision().getId().equals(w.getAcceptedRevision().getId())).toList();
            if(candidates.size()!=1)throw ApiExceptions.conflict("Unique allocated preparation payment required");
            payment=write?payments.findByIdForUpdate(candidates.getFirst().getId()).orElse(null):candidates.getFirst();
            if(!RenewalPreparationResolution.blocked(payment)||events(n,"PREPARATION_BLOCKED").stream()
                .noneMatch(event->payment.getId().toString().equals(json(event).path("paymentId").asText())))
                throw ApiExceptions.conflict("Server-recorded preparation failure required");
        } else {
            if(s.getDepositPaymentRef()==null||s.getDepositPaidAt()==null||s.getEffectiveSigningDeadline()==null)
                throw ApiExceptions.conflict("Verified deposit and accepted revision required");
            if(s.getRecoveryCutoff()==null||s.getOriginalSigningDeadline()==null
            ||!s.getOriginalSigningDeadline().equals(com.storagehub.service.renewal.RenewalSigningDeadline.calculate(s.getDepositPaidAt(),s.getRecoveryCutoff(),accepted))
            ||s.getEffectiveSigningDeadline().isAfter(s.getRecoveryCutoff())
            ||s.getConfirmedExceptionRef()==null&&!s.getEffectiveSigningDeadline().equals(s.getOriginalSigningDeadline()))
                throw ApiExceptions.conflict("Signing deadline requires reconciliation before deposit settlement");
            payment=write?payments.findByIdForUpdate(s.getDepositPaymentRef()).orElse(null):em.find(Payment.class,s.getDepositPaymentRef());
        }
        if(payment==null||payment.getStatus()!=PaymentStatus.PAID||payment.getPurpose()!=PaymentType.RENEWAL||payment.getRenewalRevision()==null
            ||!n.getId().equals(payment.getRenewal().getId())||!w.getAcceptedRevision().getId().equals(payment.getRenewalRevision().getId())
            ||payment.getRenewalLedgerReceiptRef()==null||payment.getRefundedAmount()==null||payment.getPaidAt()==null
            ||payment.getPaidAt().isAfter(now())||!unprepared&&!payment.getPaidAt().equals(s.getDepositPaidAt()))
            throw ApiExceptions.conflict("Deposit Payment binding is invalid");
        var classification=provenance.classify(payment);
        if(!Set.of(PaymentProvenance.Classification.SANDBOX,PaymentProvenance.Classification.VERIFIED).contains(classification))
            throw ApiExceptions.conflict("Deposit payment provenance has not been reconciled");
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
