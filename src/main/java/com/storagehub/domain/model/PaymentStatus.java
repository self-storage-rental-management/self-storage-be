package com.storagehub.domain.model;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    PAID,
    FAILED,
    EXPIRED,
    RECONCILIATION_REQUIRED,
    REFUND_PENDING,
    REFUNDED,
    CANCELLED
}
