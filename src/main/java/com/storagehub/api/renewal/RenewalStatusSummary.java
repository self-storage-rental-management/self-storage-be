package com.storagehub.api.renewal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Independent read models; none of these display statuses rewrites persisted workflow/payment enums. */
public record RenewalStatusSummary(
    @io.swagger.v3.oas.annotations.media.Schema(implementation=com.storagehub.api.StatusContract.RenewalWorkflowStatus.class)
    String workflowStatus, Invoice invoice, List<Transaction> transactions, Instant checkedAt) {
    public RenewalStatusSummary {
        com.storagehub.api.StatusContract.RenewalWorkflowStatus.valueOf(workflowStatus);
    }
    public record Invoice(
        @io.swagger.v3.oas.annotations.media.Schema(implementation=com.storagehub.api.StatusContract.InvoiceStatus.class) String status,
        @io.swagger.v3.oas.annotations.media.Schema(implementation=com.storagehub.api.StatusContract.InvoiceRefundStatus.class) String refundStatus,
        BigDecimal totalBeforeAdjustments,
        BigDecimal discountAmount, BigDecimal adjustmentAmount, BigDecimal totalAfterAdjustments,
        BigDecimal paidAmount, BigDecimal outstandingAmount, BigDecimal dueNowAmount, BigDecimal overdueAmount,
        BigDecimal refundedAmount, String currency, String reason) {
        public Invoice {
            com.storagehub.api.StatusContract.InvoiceStatus.valueOf(status);
            com.storagehub.api.StatusContract.InvoiceRefundStatus.valueOf(refundStatus);
        }
        public static Invoice unknown(String status, String reason) {
            return new Invoice(status,"UNKNOWN",null,null,null,null,null,null,null,null,null,"VND",reason);
        }
    }
    public record Transaction(UUID paymentId, String reference, String purpose,
        @io.swagger.v3.oas.annotations.media.Schema(implementation=com.storagehub.api.StatusContract.PaymentTransactionStatus.class) String status,
        BigDecimal amount, BigDecimal refundedAmount, String currency, Instant paymentDeadline,
        boolean deadlineExpired, boolean receiptAvailable, Instant paidAt) {
        public Transaction {
            com.storagehub.api.StatusContract.PaymentTransactionStatus.valueOf(status);
        }
    }
}
