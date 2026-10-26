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
        if (payment.getRenewalRevision() == null) {
            var end = payment.getPaidAt().plus(RenewalSigningDeadline.WINDOW);
            return state != null && state.getRecoveryCutoff() != null && state.getRecoveryCutoff().isBefore(end) ? state.getRecoveryCutoff() : end;
        }
        return deadline(payment, state, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    }
    public static Instant deadline(Payment payment, RenewalOperationState state, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (payment.getPaidAt() == null) return null;
        java.time.Duration window = RenewalSigningDeadline.WINDOW;
        var revision = payment.getRenewalRevision();
        if (revision != null && revision.getQuote() != null) {
            try {
                var terms = mapper.readValue(revision.getQuote().getTermsJson(), com.storagehub.api.renewal.RenewalQuoteResponse.Terms.class);
                window = RenewalSigningDeadline.window(terms);
            } catch (Exception invalid) {
                throw com.storagehub.common.api.ApiExceptions.conflict("Accepted signing window requires reconciliation");
            }
        } else {
            throw com.storagehub.common.api.ApiExceptions.conflict("Accepted signing terms missing");
        }
        var end = payment.getPaidAt().plus(window);
        return state != null && state.getRecoveryCutoff() != null && state.getRecoveryCutoff().isBefore(end)
            ? state.getRecoveryCutoff() : end;
    }
}
