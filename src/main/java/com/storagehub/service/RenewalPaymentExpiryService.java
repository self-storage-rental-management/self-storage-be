package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queries expired Renewal intents before releasing their capacity hold or open workflow slot. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-payment-lifecycle.enabled",havingValue="true")
public class RenewalPaymentExpiryService {
    public static final String MANUAL_REVIEW = "PAYMENT_RECONCILIATION_PENDING";
    private static final String LATE_PAYMENT = "PAYMENT_AFTER_DEADLINE";
    private static final List<PaymentStatus> UNRESOLVED=List.of(PaymentStatus.PENDING,PaymentStatus.PROCESSING,PaymentStatus.NOT_RECEIVED);
    private final PaymentRepository payments;
    private final VnpayService vnpay;
    private final EntityManager em;
    private final RenewalPersistence renewals;
    private final ObjectMapper mapper;
    private final AuditLogService audit;
    private final NotificationService notifications;
    private final ObjectProvider<RenewalOperationSources.SafetySource> safetySources;
    @org.springframework.beans.factory.annotation.Value("${storagehub.integration.renewal-simple-payment.enabled:false}")
    private boolean simplePayment;
    @org.springframework.beans.factory.annotation.Value("${storagehub.renewal.payment-reconciliation-minutes:30}")
    private long reconciliationMinutes = 30;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<RenewalOperationSources.CalendarSource> signingCalendars;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.ledger.RenewalDepositObligationWriter> depositWriters;

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public int reconcileExpiredIntents() {
        Instant now=Instant.now();
        var candidates=new ArrayList<Payment>();
        for(PaymentStatus status:UNRESOLVED)candidates.addAll(payments.findByStatus(status));
        candidates.addAll(payments.findByStatus(PaymentStatus.FAILED));
        candidates.addAll(payments.findByStatus(PaymentStatus.CANCELLED));
        candidates.addAll(payments.findByPurposeAndFailureCode(PaymentType.RENEWAL, LATE_PAYMENT));
        int completed=0;
        for(Payment candidate:candidates) {
            if(candidate.getRenewal()==null||!List.of(PaymentType.RENEWAL,PaymentType.RENEWAL_INSTALLMENT).contains(candidate.getPurpose())
                ||candidate.getPaymentDeadline()==null||candidate.getPaymentDeadline().isAfter(now))continue;
            Payment payment=payments.findByIdForUpdate(candidate.getId()).orElse(null);
            if(payment==null||payment.getRenewal()==null||!List.of(PaymentType.RENEWAL,PaymentType.RENEWAL_INSTALLMENT).contains(payment.getPurpose())
                ||payment.getPaymentDeadline()==null||payment.getPaymentDeadline().isAfter(now))continue;
            if(UNRESOLVED.contains(payment.getStatus())) {
                if (payment.getPurpose()==PaymentType.RENEWAL && payment.getRenewal().getStatus()==RenewalStatus.payment_expired) continue;
                VnpayService.ReconciliationOutcome outcome=vnpay.reconcileAtPaymentDeadline(payment);
                payments.saveAndFlush(payment);
                if(outcome!=VnpayService.ReconciliationOutcome.FINAL_FAILURE) {
                    if (payment.getPurpose()==PaymentType.RENEWAL && (LATE_PAYMENT.equals(payment.getFailureCode())
                        || UNRESOLVED.contains(payment.getStatus()) && !now.isBefore(manualReviewAt(payment.getPaymentDeadline(), reconciliationMinutes)))
                        && expireIfSafe(payment.getRenewal(),payment,true)) completed++;
                    continue;
                }
            } else if (payment.getStatus()==PaymentStatus.PAID && LATE_PAYMENT.equals(payment.getFailureCode())) {
                if (expireIfSafe(payment.getRenewal(),payment,true)) completed++;
                continue;
            } else if(payment.getStatus()!=PaymentStatus.FAILED&&payment.getStatus()!=PaymentStatus.CANCELLED)continue;
            if(payment.getPurpose()==PaymentType.RENEWAL_INSTALLMENT)continue; // A failed later period never expires the signed Renewal.
            if(expireIfSafe(payment.getRenewal(),payment,false))completed++;
        }
        return completed;
    }

    public static Instant manualReviewAt(Instant deadline, long minutes) {
        return deadline.plus(java.time.Duration.ofMinutes(Math.max(1, minutes)));
    }

    private boolean expireIfSafe(Renewal paymentRenewal,Payment payment,boolean manualReview) {
        Renewal renewal=em.find(Renewal.class,paymentRenewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(renewal==null||!List.of(RenewalStatus.accepted,RenewalStatus.payment_expired).contains(renewal.getStatus()))return false;
        var workflow=em.find(RenewalWorkflow.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(workflow==null||workflow.getPaymentDeadline()==null||workflow.getPaymentDeadline().isAfter(Instant.now()))return false;
        var paid=payments.findAllByRenewal_IdAndStatusIn(renewal.getId(),List.of(PaymentStatus.PAID));
        if (manualReview) {
            if (renewal.getStatus()!=RenewalStatus.accepted || paid.stream().anyMatch(p -> !LATE_PAYMENT.equals(p.getFailureCode()))) return false;
        } else if (payments.existsByRenewal_IdAndStatusIn(renewal.getId(),UNRESOLVED) || !paid.isEmpty()) return false;
        if (renewal.getStatus()==RenewalStatus.payment_expired) {
            if (!MANUAL_REVIEW.equals(workflow.getCancellationReason())) return false;
            cancelUnpaid(workflow);
            workflow.cancelled("PAYMENT_RECONCILIATION_FAILED");
            em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            audit.recordMutation((User)null,"RENEWAL_PAYMENT_REVIEW_CLOSED","Renewal",renewal.getId(),
                renewal.getRental().getFacility().getId(),null,Map.of("paymentId",payment.getId()));
            notifications.createNotification(renewal.getRental().getCustomer().getId(),NotificationType.RENEWAL,
                "Đã kết thúc đối soát thanh toán gia hạn","Giao dịch không thành công. Bạn có thể gửi yêu cầu gia hạn mới nếu còn đủ điều kiện.",renewal.getId());
            return true;
        }
        boolean holdReleased=false;
        if(workflow.getExtensionHoldRef()!=null) {
            var safety=safetySources.getIfAvailable();if(safety==null||!safety.atomic())return false;
            safety.release(renewal,workflow.getExtensionHoldRef());holdReleased=true;
        }else if(!simplePayment)return false;
        if (!manualReview) cancelUnpaid(workflow);
        if (manualReview) workflow.cancelled(MANUAL_REVIEW);
        renewal.setStatus(RenewalStatus.payment_expired);
        var state=em.find(RenewalOperationState.class,renewal.getId(),LockModeType.PESSIMISTIC_WRITE);
        boolean newState=state==null;
        if(newState)state=new RenewalOperationState(renewal);
        if(state.getAppointmentRef()!=null) {
            var calendar=signingCalendars==null?null:signingCalendars.getIfAvailable();
            if(calendar==null||!calendar.atomic())throw com.storagehub.common.api.ApiExceptions.conflict("Signing calendar release unavailable");
            calendar.release(renewal,Instant.now());
        }
        state.expire(false);
        if(newState)em.persist(state);
        renewals.releaseSlot(workflow);
        em.lock(workflow,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        var payload=Map.of("paymentId",payment.getId(),"responseCode",Objects.toString(payment.getGatewayResponseCode(),"UNKNOWN"),
            "expiredAt",Instant.now().toString(),"holdReleased",holdReleased,"manualReview",manualReview);
        try { em.persist(new RenewalOperationEvent(renewal,"EXPIRY",Instant.now(),null,mapper.writeValueAsString(payload))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Could not persist Renewal payment expiry event",invalid);}
        audit.recordMutation((User)null,"RENEWAL_PAYMENT_EXPIRED","Renewal",renewal.getId(),renewal.getRental().getFacility().getId(),
            Map.of("status",RenewalStatus.accepted),Map.of("status",RenewalStatus.payment_expired,"paymentId",payment.getId()));
        notifications.createNotification(renewal.getRental().getCustomer().getId(),NotificationType.RENEWAL,
            "Yêu cầu thanh toán gia hạn đã hết hạn",
            manualReview ? "Yêu cầu đã đóng và lịch ký đã được giải phóng. Giao dịch cũ đang chờ đối soát; chưa thể thanh toán lượt mới."
                : "Yêu cầu thanh toán gia hạn đã hết hạn và được đóng.",renewal.getId());
        notifications.createFacilityOperationsNotifications(renewal.getRental().getFacility().getId(),NotificationType.RENEWAL,
            manualReview ? "Cần đối soát giao dịch gia hạn đã đóng" : "Yêu cầu gia hạn đã hết hạn thanh toán",
            "Yêu cầu gia hạn của khách hàng "+renewal.getRental().getCustomer().getFullName()+" đã hết hạn thanh toán; giữ chỗ gia hạn và lịch ký liên quan đã được giải phóng.",renewal.getId());
        return true;
    }

    private void cancelUnpaid(RenewalWorkflow workflow) {
        if (!simplePayment) return;
        var writer=depositWriters.getIfAvailable();
        if(writer==null)throw com.storagehub.common.api.ApiExceptions.conflict("Cancellation accounting source unavailable");
        writer.cancelUnpaid(workflow);
    }
}
