package com.storagehub.service.renewal;

import com.storagehub.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A gateway PAID row alone does not certify a reconciled transaction or an invoice. */
public final class RenewalTransactionStatus {
    private RenewalTransactionStatus() {}
    public static String resolve(Payment p, boolean reconciled, BigDecimal executedRefund, Instant now) {
        boolean expired=p.getPaymentDeadline()!=null&&!now.isBefore(p.getPaymentDeadline());
        if(p.getStatus()==PaymentStatus.PAID) {
            if(!reconciled||p.getRefundedAmount()!=null&&p.getRefundedAmount().compareTo(executedRefund)>0)
                return "RECONCILIATION_REQUIRED";
            if(executedRefund.signum()>0)return executedRefund.compareTo(p.getAmount())>=0?"REFUNDED":"PARTIALLY_REFUNDED";
            if(p.getFailureCode()!=null)return "RECONCILIATION_REQUIRED";
            return "SUCCEEDED";
        }
        if(p.getStatus()==PaymentStatus.CANCELLED)return "CANCELLED";
        if(p.getStatus()==PaymentStatus.FAILED)return expired&&p.getLastReconciledAt()!=null&&"91".equals(p.getFailureCode())?"EXPIRED":"FAILED";
        if(expired)return "RECONCILIATION_REQUIRED";
        if(p.getStatus()==PaymentStatus.PROCESSING)return "PROCESSING";
        return "PENDING";
    }
}
