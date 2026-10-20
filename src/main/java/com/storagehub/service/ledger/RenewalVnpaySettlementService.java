package com.storagehub.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.domain.model.*;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Converts an authenticated Renewal VNPay callback into one exact FULL_RENT subledger receipt. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class RenewalVnpaySettlementService {
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<RenewalInstallmentService> installments;
    public record Result(boolean allocated,UUID receiptId,String reason) {}
    private final EntityManager em;
    private final LedgerStore ledger;
    private final ObjectMapper mapper;
    private final ObjectProvider<RenewalOperationSources.PolicySource> policies;
    private final ObjectProvider<RenewalOperationSources.CalendarSource> calendars;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safetySources;
    private final ObjectProvider<com.storagehub.service.renewal.RenewalSources.FinancialSource> financialSources;
    private final ObjectProvider<Clock> clocks;
    private final com.storagehub.service.PaymentProvenance provenance;
    private final com.storagehub.service.NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.RenewalSources.ExtensionHoldSource> extensionHolds;
    @org.springframework.beans.factory.annotation.Value("${storagehub.integration.renewal-simple-payment.enabled:false}")
    private boolean simplePayment;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks> localPaymentChecks;

    @Transactional(propagation=Propagation.MANDATORY)
    public Result settle(Payment payment,UUID evidenceLogId) {
        if(payment!=null&&payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT){var source=installments.getIfAvailable();return source==null?new Result(false,null,"Chưa bật phân bổ tiền theo kỳ."):source.settle(payment,evidenceLogId);}
        return settleChecked(payment,evidenceLogId);
    }

    private Result settleChecked(Payment payment,UUID evidenceLogId) {
        if(payment!=null&&payment.getRenewalLedgerReceiptRef()!=null)
            return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
        if(payment==null||payment.getRenewal()==null||payment.getRenewalRevision()==null||evidenceLogId==null)
            return new Result(false,null,"Payment resource binding is incomplete");
        var renewal=em.find(Renewal.class,payment.getRenewal().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(renewal==null||renewal.getRental()==null||renewal.getRental().getCustomer()==null||renewal.getRental().getFacility()==null)
            return new Result(false,null,"Renewal source identity is incomplete");
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getAcceptedRevision()==null
            ||!Objects.equals(workflow.getAcceptedRevision().getId(),payment.getRenewalRevision().getId())
            ||renewal.getStatus()!=RenewalStatus.accepted)
            return new Result(false,null,"Payment no longer matches the current accepted Renewal revision");
        var revision=workflow.getAcceptedRevision();
        final RenewalQuoteResponse.Terms terms;
        try { terms=mapper.readValue(revision.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class); }
        catch (Exception invalid) { return new Result(false,null,"Accepted Renewal terms are invalid"); }
        final List<FinancialContractSpecifications.Specification> specifications;
        try { specifications=FinancialContractSpecifications.accepted(revision,terms); }
        catch (RuntimeException invalid) { return new Result(false,null,"Accepted Renewal terms failed financial validation"); }
        if(specifications.isEmpty()||payment.getPurpose()!=PaymentType.RENEWAL||payment.getStatus()!=PaymentStatus.PAID
            ||!"VNPAY".equals(payment.getGatewayProvider())||!"VND".equals(payment.getCurrency())
            ||payment.getGatewayTransactionNo()==null||payment.getGatewayTransactionNo().isBlank()
            ||payment.getPaidAt()==null||payment.getPaymentDeadline()==null
            ||!payment.getPaymentDeadline().equals(workflow.getPaymentDeadline())
            ||payment.getPaidAt().isAfter(payment.getPaymentDeadline())
            ||payment.getAmount()==null
            ||payment.getAmount().compareTo(specifications.getFirst().amount())!=0)
            return new Result(false,null,"Payment amount, purpose or deadline does not match initial rental Renewal terms");

        if(!Objects.equals(evidenceLogId,payment.getRenewalEvidenceRef())
            ||provenance.classify(payment)!=com.storagehub.service.PaymentProvenance.Classification.SANDBOX
            ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0)
            return new Result(false,null,"Verified resource-bound Payment evidence is unavailable");

        var now=now();
        if(payment.getPaidAt().isAfter(now))return new Result(false,null,"Gateway payment time is in the future");

        var account=ledger.read(renewal.getRental().getId()).orElse(null);
        if(account==null||!account.customerId().equals(renewal.getRental().getCustomer().getId())
            ||!account.facilityId().equals(renewal.getRental().getFacility().getId()))
            return new Result(false,null,"Renewal ledger account is unavailable or has changed binding");
        var spec=specifications.getFirst();
        var obligations=account.obligations().stream().filter(o->Objects.equals(o.renewalId(),renewal.getId())
            &&o.kind()==spec.kind()&&Objects.equals(o.sourceRef(),spec.sourceReference())
            &&o.amount().compareTo(spec.amount())==0).toList();
        if(obligations.size()!=1||obligations.getFirst().outstanding().compareTo(payment.getAmount())!=0)
            return new Result(false,null,"Unique unpaid initial rental obligation is unavailable");
        if(!ledger.renewalPaymentCommandMatches(obligations.getFirst(),spec,renewal.getRental().getCustomer().getId()))
            return new Result(false,null,"Full-rent obligation lacks the payment-ready writer evidence");
        UUID receipt=ledger.receiveSandbox(renewal.getRental().getId(),payment.getAmount(),
            Map.of(obligations.getFirst().id(),payment.getAmount()),
            new LedgerStore.GatewayProof(payment.getId(),evidenceLogId,payment.getGatewayTransactionNo()),
            renewal.getRental().getCustomer().getId(),"renewal-vnpay:"+payment.getId(),account.revision(),payment.getPaidAt());

        payment.setRenewalLedgerReceiptRef(receipt);
        payment.setRenewalAllocatedAt(now);
        em.flush();
        return new Result(true,receipt,null);
    }

    /** Transaction C: preserves the existing signing contract; does not extend Rental here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Result prepareSigning(Payment payment) {
        if(payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT){
            if(payment.getRenewalLedgerReceiptRef()==null)return new Result(false,null,"Khoản thanh toán kỳ chưa được phân bổ.");
            payment.setRenewalSigningPreparedAt(now());payment.setFailureCode(null);payment.setFailureReason(null);payment.setRenewalNextRetryAt(null);
            return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
        }
        if(payment.getRenewalSigningPreparedAt()!=null)
            return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
        if(payment.getStatus()!=PaymentStatus.PAID||payment.getRenewalAllocatedAt()==null
            ||payment.getRenewalLedgerReceiptRef()==null||payment.getRenewalRevision()==null
            ||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0
            ||provenance.classify(payment)!=com.storagehub.service.PaymentProvenance.Classification.SANDBOX)
            return new Result(false,null,"Verified initial rental allocation is required before signing");
        var renewal=em.find(Renewal.class,payment.getRenewal().getId(),LockModeType.PESSIMISTIC_WRITE);
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getAcceptedRevision()==null||renewal.getStatus()!=RenewalStatus.accepted
            ||!Objects.equals(workflow.getAcceptedRevision().getId(),payment.getRenewalRevision().getId()))
            return new Result(false,null,"Current accepted revision no longer permits signing preparation");
        final RenewalQuoteResponse.Terms terms;
        try { terms=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class); }
        catch(Exception invalid) { return new Result(false,null,"Accepted terms are invalid"); }
        var now=now();
        var safety=safetySources.getIfAvailable();var policySource=policies.getIfAvailable();var calendar=calendars.getIfAvailable();
        if(safety==null||!safety.atomic()||calendar==null||!calendar.atomic())
            return new Result(false,null,"Atomic signing and Recovery sources are unavailable");
        var chosen=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        boolean selected=chosen!=null&&chosen.getAppointmentRef()!=null&&chosen.getAppointmentStart()!=null&&chosen.getAppointmentEnd()!=null;
        Instant selectedDeadline=payment.getPaidAt().plus(com.storagehub.service.renewal.RenewalSigningDeadline.WINDOW);
        String deadlineRef=com.storagehub.service.renewal.RenewalSigningDeadline.REFERENCE;
        String deadlineVersion=com.storagehub.service.renewal.RenewalSigningDeadline.VERSION;
        UUID hold=workflow.getExtensionHoldRef();
        if(hold==null&&simplePayment) {
            var holdSource=extensionHolds==null?null:extensionHolds.getIfAvailable();
            if(holdSource==null||!holdSource.participatesInTransaction())return new Result(false,null,"Signing extension hold is unavailable");
            var local=localPaymentChecks==null?null:localPaymentChecks.getIfAvailable();
            if(local==null)return new Result(false,null,"Published signing cutoff is unavailable");
            Instant prospectiveDeadline=min(selectedDeadline,local.cutoff(renewal.getRental(),now));
            if(!prospectiveDeadline.isAfter(now))return new Result(false,null,"Signing deadline has passed");
            hold=holdSource.acquire(renewal.getRental(),renewal.getId(),terms.extensionStartDate(),terms.extensionEndExclusive(),prospectiveDeadline);
            if(hold==null)return new Result(false,null,"Signing extension hold did not return a reference");
            workflow.signingHold(hold);
        }
        Instant cutoff=safety.check(renewal,terms,hold,now);
        // Callback arrival time does not decide whether the customer paid before the deadline.
        if(cutoff==null||payment.getPaidAt().isAfter(payment.getPaymentDeadline())||payment.getPaymentDeadline().isAfter(cutoff))
            return new Result(false,null,"Extension rights require reconciliation");
        Instant signingDeadline=com.storagehub.service.renewal.RenewalSigningDeadline.calculate(payment.getPaidAt(),cutoff);
        if(selected&&(chosen.getAppointmentStart().isBefore(payment.getPaidAt())||chosen.getAppointmentEnd().isAfter(signingDeadline)))
            return new Result(false,null,"Selected signing appointment is outside the paid signing window");
        boolean missedAppointment=selected&&!chosen.getAppointmentEnd().isAfter(now);
        var selectedSlot=selected&&!missedAppointment?calendar.slot(renewal,chosen.getAppointmentStart()).orElse(null):null;
        boolean calendarReady=selected
            ? missedAppointment||selectedSlot!=null&&selectedSlot.end().equals(chosen.getAppointmentEnd())
            : calendar.feasible(renewal,now,signingDeadline);
        if(!signingDeadline.isAfter(now)||!calendarReady)
            return new Result(false,null,"No signing slot is available before the Recovery cutoff");
        var financial=financialSources.getIfAvailable();
        var financialState=financial==null?null:financial.read(renewal.getRental()).orElse(null);
        var remainderIds=new java.util.HashSet<UUID>();
        var account=ledger.read(renewal.getRental().getId()).orElse(null);
        if(terms.renewalDepositAmount().signum()>0&&account!=null) {
            var specs=FinancialContractSpecifications.signing(FinancialContractSpecifications.accepted(workflow.getAcceptedRevision(),terms));
            var remainder=specs.getLast();
            var rows=account.obligations().stream().filter(o->Objects.equals(o.renewalId(),renewal.getId())
                &&o.kind()==remainder.kind()&&o.sourceRef().equals(remainder.sourceReference())
                &&o.amount().compareTo(remainder.amount())==0&&o.refunded().signum()==0
                &&ledger.renewalPaymentCommandMatches(o,remainder,renewal.getRental().getCustomer().getId())).toList();
            if(rows.size()!=1)return new Result(false,null,"Accepted remainder obligation is unavailable");
            remainderIds.add(rows.getFirst().id());
        }
        if(simplePayment) {
            var local=localPaymentChecks==null?null:localPaymentChecks.getIfAvailable();
            if(local==null)return new Result(false,null,"Local payment checks unavailable");
            local.requireKnownFinance(renewal.getRental(),renewal.getId(),now);
        } else if(financial==null||!financial.consistentThroughApproval()||financialState==null||financialState.checkedAt()==null
            ||financialState.checkedAt().isAfter(now)||financialState.dueObligations()==null
            ||financialState.unresolvedDispute()||financialState.dueObligations().stream().anyMatch(id->!remainderIds.contains(id)))
            return new Result(false,null,"Financial state is unknown or still blocks signing");
        var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(state!=null&&state.hasVerifiedPayment())
            return new Result(false,null,"Renewal already has a different verified payment state");
        safety.retain(renewal,hold,signingDeadline);

        boolean newState=state==null;
        if(newState)state=new RenewalOperationState(renewal);
        boolean deposit=terms.renewalDepositAmount().signum()>0;
        if(deposit)state.deposit(payment.getId(),payment.getPaidAt(),signingDeadline,cutoff,deadlineRef,deadlineVersion);
        else state.fullRent(payment.getId(),payment.getPaidAt(),signingDeadline,cutoff,deadlineRef,deadlineVersion);
        if(newState)em.persist(state);
        renewal.setStatus(state.getAppointmentRef()!=null?RenewalStatus.appointment_scheduled:deposit?RenewalStatus.deposit_paid:RenewalStatus.full_rent_paid);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        var eventData=Map.of("paymentRef",payment.getId(),"renewalId",renewal.getId(),"amount",payment.getAmount(),"currency",payment.getCurrency(),
            "paidAt",payment.getPaidAt(),"paymentDeadline",payment.getPaymentDeadline(),"receiptId",payment.getRenewalLedgerReceiptRef(),"outcome","SUCCESS");
        try {
            em.persist(new RenewalOperationEvent(renewal,deposit?"DEPOSIT":"FULL_RENT_PAYMENT",payment.getPaidAt(),null,mapper.writeValueAsString(eventData)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Could not store Renewal payment event",invalid);
        }
        em.flush();
        payment.setRenewalSigningPreparedAt(now);
        payment.setFailureCode(null);payment.setFailureReason(null);payment.setRenewalNextRetryAt(null);
        notifications.createNotification(renewal.getRental().getCustomer().getId(),NotificationType.RENEWAL,
            deposit?"Đã nhận thanh toán cọc gia hạn":"Đã nhận thanh toán gia hạn",
            "Khoản thanh toán "+payment.getAmount()+" "+payment.getCurrency()+" cho hồ sơ thuê "+renewal.getRental().getId()
                +" đã được xác nhận. Tiếp tục theo dõi lịch ký gia hạn trong hồ sơ.",renewal.getId());
        return new Result(true,payment.getRenewalLedgerReceiptRef(),null);
    }

    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
}
