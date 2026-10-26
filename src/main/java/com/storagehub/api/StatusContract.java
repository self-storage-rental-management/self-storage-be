package com.storagehub.api;

/** Read-model states are separate contracts, never persisted Payment/Reservation/Rental states. */
public final class StatusContract {
    private StatusContract() {}
    public enum InvoiceStatus { NOT_ISSUED, UNPAID, PARTIALLY_PAID, PAID, CANCELLED, UNKNOWN }
    public enum InvoiceRefundStatus { NONE, PENDING, PARTIALLY_REFUNDED, REFUNDED, UNKNOWN }
    public enum PaymentTransactionStatus { PENDING, PROCESSING, RECONCILIATION_REQUIRED, SUCCEEDED, FAILED, CANCELLED, EXPIRED, PARTIALLY_REFUNDED, REFUNDED }
    public enum RenewalWorkflowStatus { UNDER_REVIEW, AWAITING_PAYMENT, AWAITING_RECONCILIATION, AWAITING_SIGNING, SIGNING_IN_PROGRESS, SIGNING_EXPIRED, PAYMENT_WINDOW_EXPIRED, ON_HOLD, COMPLETED, CANCELLED, REJECTED }
}
