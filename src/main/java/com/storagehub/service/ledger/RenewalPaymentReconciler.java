package com.storagehub.service.ledger;

import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.repo.PaymentRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment rows are the persistent work queue. Each stage commits independently. */
@Service
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-obligations.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
public class RenewalPaymentReconciler {
    public record Verified(UUID paymentId) {}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RenewalPaymentReconciler.class);
    private final PaymentRepository payments;
    private final RenewalVnpaySettlementService settlement;
    private final TransactionTemplate transaction;

    public RenewalPaymentReconciler(PaymentRepository payments,RenewalVnpaySettlementService settlement,
        PlatformTransactionManager manager) {
        this.payments=payments;this.settlement=settlement;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @TransactionalEventListener
    public void afterVerifiedPaymentCommit(Verified event) { reconcile(event.paymentId()); }

    public void retryPending() {
        for(UUID id:payments.findRenewalReconciliationCandidates(Instant.now(),PageRequest.of(0,100)))reconcile(id);
    }

    public void reconcile(UUID id) {
        try {
            Boolean allocated=transaction.execute(status->{
                var payment=payments.findByIdForUpdate(id).orElse(null);
                if(!eligible(payment))return false;
                boolean previouslyAllocated=payment.getRenewalAllocatedAt()!=null&&payment.getRenewalLedgerReceiptRef()!=null;
                var result=settlement.settle(payment,payment.getRenewalEvidenceRef());
                if(!result.allocated())retry(payment,result.reason());
                else if(!previouslyAllocated)payment.setRenewalReconciliationAttempts(0);
                payments.saveAndFlush(payment);
                return result.allocated();
            });
            if(!Boolean.TRUE.equals(allocated))return;
            transaction.executeWithoutResult(status->{
                var payment=payments.findByIdForUpdate(id).orElse(null);
                if(!eligible(payment))return;
                var result=settlement.prepareSigning(payment);
                if(!result.allocated())retry(payment,result.reason());
                payments.saveAndFlush(payment);
            });
        } catch(RuntimeException unavailable) {
            LOG.warn("Renewal payment {} requires reconciliation; verified funds remain committed",id);
            LOG.error("Renewal payment reconciliation failure: {}", unavailable.getMessage(), unavailable);
            try {
                transaction.executeWithoutResult(status->{
                    var payment=payments.findByIdForUpdate(id).orElse(null);
                    if(eligible(payment)) {
                        retry(payment,"Ledger or signing preparation is temporarily unavailable; reconciliation will retry");
                        payments.saveAndFlush(payment);
                    }
                });
            } catch(RuntimeException databaseUnavailable) {
                // The previously committed PAID/evidence row remains discoverable on recovery.
                LOG.warn("Could not update retry metadata for Renewal payment {}",id);
            }
        }
    }

    private boolean eligible(Payment payment) {
        return payment!=null&&payment.getRenewal()!=null&&payment.getStatus()==PaymentStatus.PAID
            &&payment.getRenewalEvidenceRef()!=null&&payment.getRenewalSigningPreparedAt()==null
            &&"RENEWAL_PAYMENT_RECONCILIATION_REQUIRED".equals(payment.getFailureCode());
    }
    private void retry(Payment payment,String reason) {
        payment.setRenewalReconciliationAttempts(payment.getRenewalReconciliationAttempts()+1);
        payment.setFailureReason(reason==null?"Reconciliation required":reason.substring(0,Math.min(1000,reason.length())));
        payment.setRenewalNextRetryAt(Instant.now().plusSeconds(Math.min(300,30L*payment.getRenewalReconciliationAttempts())));
        settlement.holdForPreparationResolution(payment);
    }
}
