package com.storagehub.api.payment;

import java.math.BigDecimal;
import java.time.Instant;

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
    Instant occurredAt
) {}
