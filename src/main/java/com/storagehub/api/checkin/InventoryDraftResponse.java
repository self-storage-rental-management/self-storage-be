package com.storagehub.api.checkin;

import java.time.Instant;

/**
 * The saved inventory form before Staff publishes the capacity result.
 *
 * The revision intentionally uses the CheckIn optimistic-lock version so a
 * draft cannot silently overwrite a newer handover or identity update.
 */
public record InventoryDraftResponse(
    CompleteCheckInRequest request,
    long revision,
    Instant savedAt
) {}
