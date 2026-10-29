package com.storagehub.api.checkin;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import com.storagehub.domain.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CheckInPaymentResponse(
    UUID paymentId,
    UUID reservationId,
    BigDecimal amount,
    String currency,
    PaymentType purpose,
    PaymentStatus status,
    String transactionReference,
    String paymentMethod,
    Instant paidAt,
    Instant processedAt,
    String initiatedByName,
    String invoiceNumber,
    InvoiceStatus invoiceStatus,
    Instant invoiceIssuedAt
) {
}
