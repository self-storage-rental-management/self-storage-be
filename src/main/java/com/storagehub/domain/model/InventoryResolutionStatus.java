package com.storagehub.domain.model;

/**
 * Customer-facing outcome of the first inventory comparison.
 * A mismatch is recorded before any acceptance, partial acceptance or return
 * decision is applied.
 */
public enum InventoryResolutionStatus {
    NOT_STARTED,
    PENDING_CUSTOMER,
    PENDING_STAFF_RESOLUTION,
    ACCEPTED,
    PARTIAL_ACCEPTED,
    RETURN_REQUESTED,
    RECOVERY_REQUIRED
}
