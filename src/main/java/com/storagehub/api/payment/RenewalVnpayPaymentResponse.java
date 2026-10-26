package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RenewalVnpayPaymentResponse(
    UUID paymentId,
    UUID renewalId,
    UUID acceptedRevisionId,
    String transactionReference,
    BigDecimal amount,
    String currency,
    PaymentStatus paymentStatus,
    Instant paymentDeadline,
    Instant paidAt,
    boolean reconciliationRequired,
    String message,
    String paymentUrl,
    String settlementStage,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt
) {}
