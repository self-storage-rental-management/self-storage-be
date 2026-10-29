package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VnpayTransactionResponse(
    UUID paymentId,
    UUID reservationId,
    String transactionReference,
    String gatewayTransactionNo,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    PaymentStatus paymentStatus,
    ReservationStatus reservationStatus,
    String responseCode,
    String transactionStatus,
    String gatewayPayDate,
    String message,
    Instant processedAt,
    Instant lastReconciledAt,
    PaymentType purpose,
    String paymentMethod,
    Instant paidAt,
    String initiatedByName,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt
) {}
