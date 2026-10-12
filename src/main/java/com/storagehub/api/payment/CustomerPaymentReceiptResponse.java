package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerPaymentReceiptResponse(
    UUID paymentId,
    UUID reservationId,
    String transactionReference,
    String gatewayTransactionNo,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    PaymentStatus paymentStatus,
    PaymentType purpose,
    ReservationStatus reservationStatus,
    String responseCode,
    String transactionStatus,
    String gatewayPayDate,
    String paymentMethod,
    String message,
    Instant processedAt,
    Instant paidAt,
    Instant lastReconciledAt,
    String initiatedByName,
    Instant createdAt,
    String reservationCode
) {
}
