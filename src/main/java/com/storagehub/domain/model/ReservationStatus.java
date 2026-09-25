package com.storagehub.domain.model;

public enum ReservationStatus {
    CREATED,
    awaiting_email,
    awaiting_review,
    awaiting_payment,
    DEPOSIT_PAID,
    UNIT_RESERVED,
    READY_FOR_CHECKIN,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
