package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentIntentResponse(
    UUID id,
    UUID reservationId,
    BigDecimal amount,
    String currency,
    PaymentType purpose,
    PaymentStatus status,
    String provider,
    String gatewayIntentId,
    Instant createdAt
) {
}
