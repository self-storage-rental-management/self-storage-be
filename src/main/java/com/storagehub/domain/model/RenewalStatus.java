package com.storagehub.domain.model;

public enum RenewalStatus {
    pending,
    deposit_paid,
    approved,
    appointment_scheduled,
    payment_processing,
    payment_failed,
    payment_expired,
    rejected,
    cancelled,
    completed
}
