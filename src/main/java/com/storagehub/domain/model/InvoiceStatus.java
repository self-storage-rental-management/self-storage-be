package com.storagehub.domain.model;

/**
 * The invoice lifecycle is deliberately separate from PaymentStatus. A payment
 * can be pending or failed while its invoice metadata remains a durable audit
 * record, and a paid payment can later become refunded.
 */
public enum InvoiceStatus {
    DRAFT,
    PAID,
    VOID,
    REFUNDED
}
