package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentSimulationOutcome;
import com.storagehub.domain.model.PaymentStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SimulatedPaymentResponse(
    UUID paymentId,
    UUID reservationId,
    BigDecimal amount,
    String currency,
    PaymentSimulationOutcome outcome,
    PaymentStatus paymentStatus,
    ReservationStatus reservationStatus,
    String message,
    Instant processedAt
) {
}
