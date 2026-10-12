package com.storagehub.service.ledger;

import com.storagehub.service.PaymentProvenance.Classification;
import com.storagehub.domain.model.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Project scope only. Classification must come from transaction-level PaymentProvenance. No Ledger credit. */
@Component
public class SandboxPaymentRecognitionPolicy {
    private final String environment;
    private final boolean enabled;
    public record Decision(String environment,boolean enabled,boolean eligibleForReconciliation,String reason) {}
    public SandboxPaymentRecognitionPolicy(
        @Value("${storagehub.project.payment-environment:${STORAGEHUB_PROJECT_PAYMENT_ENVIRONMENT:VNPAY_SANDBOX}}") String environment,
        @Value("${storagehub.project.sandbox-recognition-enabled:${STORAGEHUB_SANDBOX_RECOGNITION_ENABLED:true}}") boolean enabled) {
        this.environment=environment;this.enabled=enabled;
    }
    public Decision evaluate(Payment payment,Classification classification) {
        boolean active=enabled&&"VNPAY_SANDBOX".equals(environment);
        String reason=!active?"SANDBOX_POLICY_DISABLED":classification==Classification.SANDBOX
            ?"AUTHENTICATED_SANDBOX_EVIDENCE":classification==Classification.VERIFIED
            ?"PRODUCTION_PAYMENT_OUT_OF_SCOPE":"AUTHENTICATED_SANDBOX_EVIDENCE_REQUIRED";
        boolean eligible=active&&classification==Classification.SANDBOX;
        if(eligible&&(payment==null||payment.getRefundedAmount()==null||payment.getRefundedAmount().signum()!=0)) {
            eligible=false;reason="REFUND_RECONCILIATION_REQUIRED";
        }
        return new Decision(environment,active,eligible,reason);
    }
}
