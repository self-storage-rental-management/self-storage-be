package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.service.PaymentProvenance.Classification;
import com.storagehub.domain.model.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SandboxPaymentRecognitionPolicyTests {
    @ParameterizedTest @EnumSource(Classification.class)
    void onlyHistoricallyAuthenticatedSandboxEvidenceIsEligible(Classification classification) {
        var result=new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",true).evaluate(new Payment(),classification);
        assertThat(result.eligibleForReconciliation()).isEqualTo(classification==Classification.SANDBOX);
        assertThat(result.environment()).isEqualTo("VNPAY_SANDBOX");
    }
    @Test void disabledOrDifferentEnvironmentCannotRecognizeSandbox() {
        for(var policy:new SandboxPaymentRecognitionPolicy[]{new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",false),
            new SandboxPaymentRecognitionPolicy("PRODUCTION",true),new SandboxPaymentRecognitionPolicy("UNKNOWN",true)}) {
            assertThat(policy.evaluate(new Payment(),Classification.SANDBOX).eligibleForReconciliation()).isFalse();
            assertThat(policy.evaluate(new Payment(),Classification.SANDBOX).enabled()).isFalse();
        }
    }
    @Test void refundedOrUnknownRefundAmountCannotBecomeAnEligiblePayment() {
        var payment=new Payment();var policy=new SandboxPaymentRecognitionPolicy("VNPAY_SANDBOX",true);
        payment.setRefundedAmount(java.math.BigDecimal.ONE);
        assertThat(policy.evaluate(payment,Classification.SANDBOX).reason()).isEqualTo("REFUND_RECONCILIATION_REQUIRED");
        assertThat(policy.evaluate(payment,Classification.SANDBOX).eligibleForReconciliation()).isFalse();
        payment.setRefundedAmount(null);
        assertThat(policy.evaluate(payment,Classification.SANDBOX).eligibleForReconciliation()).isFalse();
    }
}
