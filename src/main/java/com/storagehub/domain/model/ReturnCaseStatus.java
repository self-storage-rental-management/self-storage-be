package com.storagehub.domain.model;

public enum ReturnCaseStatus {
    requested,
    scheduled,
    inspected,
    awaiting_customer_confirmation,
    disputed,
    payment_due,
    refund_pending,
    completed
}
