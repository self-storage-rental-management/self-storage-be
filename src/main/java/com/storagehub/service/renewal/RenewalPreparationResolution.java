package com.storagehub.service.renewal;

import com.storagehub.domain.model.*;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import java.time.Instant;

/** Operational failure after allocation is not a failed payment or a customer no-show. */
public final class RenewalPreparationResolution {
    private RenewalPreparationResolution() {}
    public static final String BLOCKED = "RENEWAL_SIGNING_PREPARATION_BLOCKED";
    public static final String RETRY = "RENEWAL_PAYMENT_RECONCILIATION_REQUIRED";
    public static final int MAX_AUTOMATIC_ATTEMPTS = 6;
    public static boolean blocked(Payment payment) {
        return payment != null && payment.getPurpose() == PaymentType.RENEWAL
            && payment.getStatus() == PaymentStatus.PAID && BLOCKED.equals(payment.getFailureCode())
            && payment.getRenewalSigningPreparedAt() == null
            && payment.getRenewalAllocatedAt() != null && payment.getRenewalLedgerReceiptRef() != null;
    }
    public static Instant deadline(Payment payment, RenewalOperationState state) {
        if (payment.getPaidAt() == null) return null;
        var end = payment.getPaidAt().plus(RenewalSigningDeadline.WINDOW);
        return state != null && state.getRecoveryCutoff() != null && state.getRecoveryCutoff().isBefore(end)
            ? state.getRecoveryCutoff() : end;
    }
}
