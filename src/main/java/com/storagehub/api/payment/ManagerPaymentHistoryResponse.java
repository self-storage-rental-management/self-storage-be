package com.storagehub.api.payment;

import java.math.BigDecimal;
import java.time.Instant;
import com.storagehub.domain.model.InvoiceStatus;

public record ManagerPaymentHistoryResponse(
    String id,
    String transactionReference,
    String customerName,
    String type,
    String status,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    String paymentMethod,
    Instant occurredAt,
    java.util.UUID renewalId,
    java.util.UUID receiptFileId,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt,
    String transactionStatus,
    Boolean receiptAvailable
) {
    public ManagerPaymentHistoryResponse(String id,String transactionReference,String customerName,String type,String status,
        BigDecimal amount,String currency,BigDecimal refundedAmount,String paymentMethod,Instant occurredAt,java.util.UUID renewalId,java.util.UUID receiptFileId) {
        this(id,transactionReference,customerName,type,status,amount,currency,refundedAmount,paymentMethod,occurredAt,renewalId,receiptFileId,null,null,null,null,null);
    }
    public ManagerPaymentHistoryResponse(String id,String transactionReference,String customerName,String type,String status,
        BigDecimal amount,String currency,BigDecimal refundedAmount,String paymentMethod,Instant occurredAt) {
        this(id,transactionReference,customerName,type,status,amount,currency,refundedAmount,paymentMethod,occurredAt,null,null,null,null,null,null,null);
    }
}
