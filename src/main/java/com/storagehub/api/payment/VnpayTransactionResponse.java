package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.ReservationStatus;
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
    String message,
    Instant processedAt,
    Instant lastReconciledAt
) {}
