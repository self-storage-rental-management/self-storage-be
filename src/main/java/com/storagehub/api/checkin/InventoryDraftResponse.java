package com.storagehub.api.checkin;
import java.time.Instant;
public record InventoryDraftResponse(CompleteCheckInRequest request, long revision, Instant savedAt) {}
