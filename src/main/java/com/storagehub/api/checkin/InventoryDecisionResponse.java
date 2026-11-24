package com.storagehub.api.checkin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Intake outcome is independent of the linked return workflow status. */
public record InventoryDecisionResponse(
    String outcome, String source, UUID decidedBy, String decidedByName,
    Instant decidedAt, String note, List<UUID> evidenceReferences,
    UUID returnCaseId, String returnStatus, LocalDate scheduledReturnDate
) {}
