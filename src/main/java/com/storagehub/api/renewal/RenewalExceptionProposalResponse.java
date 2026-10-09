package com.storagehub.api.renewal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Customer-safe view: deliberately excludes incident, reviewer, reasons and evidence IDs. */
public record RenewalExceptionProposalResponse(
    UUID renewalId, Long expectedVersion, UUID decisionRef, String status, Instant checkedAt,
    Instant currentAppointmentStart, Instant currentAppointmentEnd, Instant currentSigningDeadline,
    Instant proposedAppointmentStart, Instant proposedAppointmentEnd, Instant proposedSigningDeadline,
    Instant validUntil, boolean confirmationAllowed, List<String> disabledReasons
) {}
