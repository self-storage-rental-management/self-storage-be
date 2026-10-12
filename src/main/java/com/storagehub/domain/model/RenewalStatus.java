package com.storagehub.domain.model;

public enum RenewalStatus {
    pending,
    accepted,
    deposit_paid,
    full_rent_paid,
    approved,
    appointment_scheduled,
    payment_processing,
    payment_failed,
    payment_expired,
    rejected,
    cancelled,
    completed
}
