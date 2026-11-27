package com.storagehub.service;

import com.storagehub.domain.model.Payment;
import com.storagehub.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Revenue includes only verified real gateway or audited manual rental payments. Simulated,
 * sandbox, disputed, and complaint-approved PAID states do not prove that money was collected. */
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

    public Optional<BigDecimal> reportableCollectedRevenue(List<Payment> payments) {
        if (!realVnpayRevenueEnabled) {
            BigDecimal totalCollected = payments.stream()
                .filter(p -> p != null && p.getStatus() == PaymentStatus.PAID)
                .map(p -> p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalRefunded = payments.stream()
                .filter(p -> p != null && p.getStatus() == PaymentStatus.PAID)
                .map(p -> p.getRefundedAmount() != null ? p.getRefundedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            return Optional.of(totalCollected.subtract(totalRefunded).max(BigDecimal.ZERO));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Payment payment : payments) {
            var classification = provenance.classify(payment);
            if (classification == PaymentProvenance.Classification.UNVERIFIED
                || classification == PaymentProvenance.Classification.DISPUTED
                || classification == PaymentProvenance.Classification.ATTEMPT) {
                return Optional.empty();
            }
            if (classification == PaymentProvenance.Classification.VERIFIED) {
                BigDecimal amt = payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO;
                BigDecimal ref = payment.getRefundedAmount() != null ? payment.getRefundedAmount() : BigDecimal.ZERO;
                total = total.add(amt.subtract(ref));
            }
        }
        return Optional.of(total.max(BigDecimal.ZERO));
    }

    /** Strict validation for report consumers that require a complete collected-revenue total. */
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
