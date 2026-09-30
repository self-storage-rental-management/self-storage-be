package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentWebhookPayload(
    String eventId,
    UUID paymentId,
    PaymentStatus status,
    BigDecimal amount
) {
}
