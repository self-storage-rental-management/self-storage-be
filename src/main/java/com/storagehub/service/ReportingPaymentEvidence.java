package com.storagehub.service;

import com.storagehub.domain.model.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Revenue may include only a confirmed real gateway transaction. Booking's simulated and
 * complaint-approved PAID states do not prove that money was collected. */
@Component
public class ReportingPaymentEvidence {
    private final boolean realVnpayRevenueEnabled;
    private final PaymentProvenance provenance;

    public ReportingPaymentEvidence(PaymentProvenance provenance,
        @Value("${storagehub.reporting.real-vnpay-revenue-enabled:false}") boolean realVnpayRevenueEnabled) {
        this.realVnpayRevenueEnabled = realVnpayRevenueEnabled;
        this.provenance = provenance;
    }

    public boolean isRealCollected(Payment payment) {
        return realVnpayRevenueEnabled && payment != null
            && provenance.classify(payment) == PaymentProvenance.Classification.VERIFIED;
    }

    /** Existing numeric DTO cannot express partial coverage or pending payout. Fail explicitly. */
    public void requireReportable(java.util.List<Payment> payments) {
        if (!realVnpayRevenueEnabled) throw com.storagehub.common.api.ApiExceptions.conflict(
            "FIN_13_REVENUE_UNKNOWN: production revenue scope is not enabled; numeric zero would be misleading");
        for (Payment payment : payments) {
            var classification=provenance.classify(payment);
            if(classification==PaymentProvenance.Classification.UNVERIFIED||classification==PaymentProvenance.Classification.DISPUTED
                ||classification==PaymentProvenance.Classification.ATTEMPT)
                throw com.storagehub.common.api.ApiExceptions.conflict("FIN_13_REVENUE_PARTIAL: unreconciled Payment evidence; UNKNOWN/PARTIAL reporting contract required");
            if(classification==PaymentProvenance.Classification.VERIFIED&&payment.getRefundedAmount()!=null&&payment.getRefundedAmount().signum()>0)
                throw com.storagehub.common.api.ApiExceptions.conflict("FIN_13_REFUND_UNRECONCILED: refund request acceptance is not executed payout evidence");
        }
    }

}
