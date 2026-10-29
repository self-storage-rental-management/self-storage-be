package com.storagehub.api.payment;

import com.storagehub.domain.model.RefundRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundRequestResponse(
    UUID id,
    UUID paymentId,
    UUID returnCaseId,
    UUID requestedBy,
    UUID approvedBy,
    BigDecimal amount,
    String reason,
    RefundRequestStatus status,
    Instant createdAt,
    Instant approvedAt,
    Instant processedAt,
    String gatewayRefundId,
    String gatewayTransactionNo,
    String gatewayResponseCode,
    String rejectionReason
) {}
