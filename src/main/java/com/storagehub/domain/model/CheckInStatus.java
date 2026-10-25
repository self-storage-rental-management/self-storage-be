package com.storagehub.domain.model;

public enum CheckInStatus {
    scheduled,
    identity_verified,
    contract_signed,
    payment_collected,
    setup_in_progress,
    setup_completed,
    completed,
    rejected,
    no_show,
    cancelled
}
