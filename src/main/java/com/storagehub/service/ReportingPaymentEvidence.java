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

}
