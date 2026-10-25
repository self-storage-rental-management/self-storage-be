package com.storagehub.api.renewal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Independent read models; none of these display statuses rewrites persisted workflow/payment enums. */
public record RenewalStatusSummary(String workflowStatus, Invoice invoice, List<Transaction> transactions, Instant checkedAt) {
    public record Invoice(String status, String refundStatus, BigDecimal totalBeforeAdjustments,
        BigDecimal discountAmount, BigDecimal adjustmentAmount, BigDecimal totalAfterAdjustments,
        BigDecimal paidAmount, BigDecimal outstandingAmount, BigDecimal dueNowAmount, BigDecimal overdueAmount,
        BigDecimal refundedAmount, String currency, String reason) {
        public static Invoice unknown(String status, String reason) {
            return new Invoice(status,"UNKNOWN",null,null,null,null,null,null,null,null,null,"VND",reason);
        }
    }
    public record Transaction(UUID paymentId, String reference, String purpose, String status,
        BigDecimal amount, BigDecimal refundedAmount, String currency, Instant paymentDeadline,
        boolean deadlineExpired, boolean receiptAvailable, Instant paidAt) {}
}
