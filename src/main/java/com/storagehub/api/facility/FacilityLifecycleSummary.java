package com.storagehub.api.facility;

import com.storagehub.domain.model.FacilityStatus;
import java.util.UUID;

/**
 * Server-side checklist used before a facility is archived.
 * The counts are deliberately returned by the backend so the client cannot
 * make a lifecycle decision from an incomplete local cache.
 */
public record FacilityLifecycleSummary(
    UUID facilityId,
    FacilityStatus status,
    long pendingHoldCount,
    long processingReservationCount,
    long activeContractCount,
    long outstandingPaymentCount,
    long outstandingRefundCount
) {
    public boolean canArchive() {
        return pendingHoldCount == 0
            && processingReservationCount == 0
            && activeContractCount == 0
            && outstandingPaymentCount == 0
            && outstandingRefundCount == 0;
    }
}
