package com.storagehub.domain.model;

public enum RefundRequestStatus {
    REQUESTED,
    PENDING_ESCALATION,
    APPROVED,
    REJECTED,
    PROCESSING,
    REFUNDED,
    FAILED
}
