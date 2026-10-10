package com.storagehub.api.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record VnpayPaymentUrlResponse(
    UUID paymentId,
    UUID reservationId,
    String transactionReference,
    BigDecimal amount,
    String currency,
    String paymentUrl
) {}
